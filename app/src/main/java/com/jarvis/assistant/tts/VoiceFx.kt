package com.jarvis.assistant.tts

import com.jarvis.assistant.data.model.VoiceStyle
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Studio-style post processing that turns a plain TTS voice into a deep, polished "AI butler":
 * low-end warmth, presence, gentle compression, a faint comb/echo "digital double" and a touch of
 * room. It never imitates a particular person's voice; it only shapes tone.
 */
object VoiceFx {
    fun process(audio: PcmAudio, style: VoiceStyle): PcmAudio {
        if (style == VoiceStyle.NATURAL || audio.data.isEmpty()) return audio
        val rate = audio.sampleRate.toFloat()
        var x = toMono(audio)

        // 1. clean up: remove rumble
        x = Biquad.highPass(rate, 75f, 0.707f).run(x)

        when (style) {
            VoiceStyle.JARVIS -> {
                x = Biquad.lowShelf(rate, 190f, 5.0f).run(x)          // chest / depth
                x = Biquad.peaking(rate, 420f, 0.9f, -1.5f).run(x)    // less boxiness
                x = Biquad.peaking(rate, 2800f, 1.0f, 3.0f).run(x)    // clarity / presence
                x = Biquad.highShelf(rate, 7500f, 1.5f).run(x)        // air
                x = compress(x, rate, thresholdDb = -20f, ratio = 3.2f, attackMs = 6f, releaseMs = 90f)
                x = echoes(x, rate, listOf(0.041f to 0.04f, 0.067f to 0.025f))    // room
            }
            VoiceStyle.ROBOTIC -> {
                x = Biquad.lowShelf(rate, 220f, 3.0f).run(x)
                x = Biquad.peaking(rate, 1800f, 1.2f, 4.0f).run(x)
                x = ringMod(x, rate, hz = 62f, depth = 0.38f)
                x = compress(x, rate, thresholdDb = -22f, ratio = 4f, attackMs = 3f, releaseMs = 60f)
                x = echoes(x, rate, listOf(0.009f to 0.28f, 0.019f to 0.16f))
            }
            VoiceStyle.NATURAL -> Unit
        }

        x = softLimitAndNormalize(x, peak = 0.92f)

        // Natural pacing between sentences: short lead-in and a breath of silence at the end.
        val lead = (rate * 0.05f).toInt()
        val tail = (rate * 0.14f).toInt()
        val out = ByteArray((lead + x.size + tail) * 2)
        for (i in x.indices) {
            val s = (x[i].coerceIn(-1f, 1f) * 32767f).toInt()
            val o = (lead + i) * 2
            out[o] = (s and 0xFF).toByte()
            out[o + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return PcmAudio(out, audio.sampleRate, 1)
    }

    private fun toMono(audio: PcmAudio): FloatArray {
        val frames = audio.frames
        val out = FloatArray(frames)
        val d = audio.data
        for (f in 0 until frames) {
            var sum = 0f
            for (c in 0 until audio.channels) {
                val i = (f * audio.channels + c) * 2
                sum += ((d[i + 1].toInt() shl 8) or (d[i].toInt() and 0xFF)).toShort() / 32768f
            }
            out[f] = sum / audio.channels
        }
        return out
    }

    private fun compress(x: FloatArray, rate: Float, thresholdDb: Float, ratio: Float, attackMs: Float, releaseMs: Float): FloatArray {
        val attack = exp(-1f / (attackMs * 0.001f * rate))
        val release = exp(-1f / (releaseMs * 0.001f * rate))
        var env = 0f
        val out = FloatArray(x.size)
        val threshold = 10f.pow(thresholdDb / 20f)
        for (i in x.indices) {
            val level = abs(x[i])
            env = if (level > env) attack * env + (1 - attack) * level else release * env + (1 - release) * level
            val gain = if (env > threshold) (threshold + (env - threshold) / ratio) / env else 1f
            out[i] = x[i] * gain
        }
        return out
    }

    private fun echoes(x: FloatArray, rate: Float, taps: List<Pair<Float, Float>>): FloatArray {
        val out = x.copyOf()
        for ((seconds, mix) in taps) {
            val d = (seconds * rate).toInt()
            for (i in d until x.size) out[i] += x[i - d] * mix
        }
        return out
    }

    private fun ringMod(x: FloatArray, rate: Float, hz: Float, depth: Float): FloatArray {
        val out = FloatArray(x.size)
        for (i in x.indices) {
            val carrier = sin(2.0 * PI * hz * i / rate).toFloat()
            out[i] = x[i] * (1f - depth) + x[i] * carrier * depth
        }
        return out
    }

    private fun softLimitAndNormalize(x: FloatArray, peak: Float): FloatArray {
        var maxAbs = 0f
        for (v in x) maxAbs = max(maxAbs, abs(v))
        if (maxAbs < 1e-6f) return x
        val gain = 1.4f / maxAbs
        val out = FloatArray(x.size)
        var outMax = 0f
        for (i in x.indices) {
            out[i] = tanh(x[i] * gain)
            outMax = max(outMax, abs(out[i]))
        }
        val scale = peak / outMax
        for (i in out.indices) out[i] *= scale
        return out
    }

    /** RBJ audio-EQ-cookbook biquad. */
    internal class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        fun run(x: FloatArray): FloatArray {
            var z1 = 0f
            var z2 = 0f
            val y = FloatArray(x.size)
            for (i in x.indices) {
                val v = b0 * x[i] + z1
                z1 = b1 * x[i] - a1 * v + z2
                z2 = b2 * x[i] - a2 * v
                y[i] = v
            }
            return y
        }

        companion object {
            private fun make(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun highPass(rate: Float, hz: Float, q: Float): Biquad {
                val w = 2 * PI * hz / rate
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun lowShelf(rate: Float, hz: Float, gainDb: Float): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w = 2 * PI * hz / rate
                val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0)
                val sq = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) - (a - 1) * c + sq), 2 * a * ((a - 1) - (a + 1) * c), a * ((a + 1) - (a - 1) * c - sq),
                    (a + 1) + (a - 1) * c + sq, -2 * ((a - 1) + (a + 1) * c), (a + 1) + (a - 1) * c - sq,
                )
            }

            fun highShelf(rate: Float, hz: Float, gainDb: Float): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w = 2 * PI * hz / rate
                val c = cos(w)
                val alpha = sin(w) / 2 * sqrt(2.0)
                val sq = 2 * sqrt(a) * alpha
                return make(
                    a * ((a + 1) + (a - 1) * c + sq), -2 * a * ((a - 1) + (a + 1) * c), a * ((a + 1) + (a - 1) * c - sq),
                    (a + 1) - (a - 1) * c + sq, 2 * ((a - 1) - (a + 1) * c), (a + 1) - (a - 1) * c - sq,
                )
            }

            fun peaking(rate: Float, hz: Float, q: Float, gainDb: Float): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w = 2 * PI * hz / rate
                val alpha = sin(w) / (2 * q)
                val c = cos(w)
                return make(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
            }
        }
    }
}
