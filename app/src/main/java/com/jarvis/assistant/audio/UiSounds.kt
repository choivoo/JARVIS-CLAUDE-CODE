package com.jarvis.assistant.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.util.JLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Synthesised sci-fi interface sounds (no audio assets). Short, quiet, and switchable in Settings. */
class UiSounds(private val settings: SettingsRepository, private val scope: CoroutineScope) {
    enum class Kind { BOOT, WAKE, DONE, ERROR, GESTURE }

    fun play(kind: Kind) {
        scope.launch(Dispatchers.Default) {
            if (!settings.current().uiSounds) return@launch
            try {
                val samples = synth(kind)
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build(),
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(samples.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                delay(samples.size * 1000L / RATE + 120)
                track.release()
            } catch (e: Exception) {
                JLog.w("UiSounds", "Could not play interface sound", e)
            }
        }
    }

    companion object {
        const val RATE = 22_050

        /** Pure function so the sound design can be unit-tested. */
        fun synth(kind: Kind): ShortArray = when (kind) {
            Kind.BOOT -> sequence(
                Tone(220f, 660f, 0.55f, 0.16f), Tone(660f, 990f, 0.25f, 0.12f), Tone(1320f, 1320f, 0.35f, 0.14f),
            )
            Kind.WAKE -> sequence(Tone(660f, 880f, 0.09f, 0.18f), Tone(990f, 1320f, 0.13f, 0.18f))
            Kind.DONE -> sequence(Tone(880f, 660f, 0.12f, 0.12f))
            Kind.ERROR -> sequence(Tone(300f, 220f, 0.14f, 0.2f), Tone(220f, 160f, 0.2f, 0.2f))
            Kind.GESTURE -> sequence(Tone(1000f, 1500f, 0.06f, 0.1f))
        }

        private class Tone(val fromHz: Float, val toHz: Float, val seconds: Float, val gain: Float)

        private fun sequence(vararg tones: Tone): ShortArray {
            val parts = tones.map { render(it) }
            val out = ShortArray(parts.sumOf { it.size })
            var o = 0
            for (p in parts) {
                p.copyInto(out, o)
                o += p.size
            }
            return out
        }

        /** Sine sweep with a soft attack and exponential decay, plus a faint octave for a "glassy" timbre. */
        private fun render(t: Tone): ShortArray {
            val n = (t.seconds * RATE).toInt()
            val out = ShortArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val p = i / n.toFloat()
                val hz = t.fromHz + (t.toHz - t.fromHz) * p
                phase += 2.0 * PI * hz / RATE
                val attack = (i / (0.008f * RATE)).coerceAtMost(1f)
                val env = attack * exp(-3.2f * p)
                val v = (sin(phase) * 0.8 + sin(phase * 2) * 0.2) * env * t.gain
                out[i] = (v * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
            return out
        }
    }
}
