package com.friday.assistant.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.util.Http
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Plays raw 16-bit mono PCM straight from the network; nothing is written to disk. */
class PcmStreamPlayer(private val sampleRate: Int = 24_000) {
    private val _amp = MutableStateFlow(0f)
    val amplitude: StateFlow<Float> = _amp.asStateFlow()
    @Volatile private var track: AudioTrack? = null

    suspend fun play(read: (ByteArray) -> Int) = withContext(Dispatchers.IO) {
        coroutineScope {
            val min = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build(),
                )
                .setBufferSizeInBytes(maxOf(min, 8192))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = t
            try {
                t.play()
                val buf = ByteArray(4096)
                while (true) {
                    ensureActive()
                    val n = read(buf)
                    if (n <= 0) break
                    _amp.value = peak(buf, n)
                    t.write(buf, 0, n)
                }
                // let the tail drain before reporting done
                var last = -1
                while (t.playbackHeadPosition != last) { ensureActive(); last = t.playbackHeadPosition; kotlinx.coroutines.delay(120) }
            } finally {
                _amp.value = 0f
                runCatching { t.stop() }; t.release(); track = null
            }
        }
    }

    fun stop() { runCatching { track?.pause(); track?.flush() } }

    private fun peak(buf: ByteArray, n: Int): Float {
        var max = 0
        var i = 0
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)
            max = maxOf(max, abs(s.toShort().toInt()))
            i += 2
        }
        return (max / 32768f).coerceIn(0f, 1f)
    }
}

abstract class PcmHttpTts(protected val client: OkHttpClient = Http.client) : TTSProvider {
    private val player = PcmStreamPlayer(24_000)
    override val amplitude: StateFlow<Float> get() = player.amplitude

    protected abstract fun request(text: String): Request

    override suspend fun speak(text: String) {
        val call = client.newCall(request(text))
        val resp = withContext(Dispatchers.IO) {
            try { call.execute() } catch (e: java.io.IOException) { throw TtsException("TTS network error") }
        }
        resp.use { r ->
            if (!r.isSuccessful) throw TtsException("TTS server error ${r.code}")
            val body = r.body ?: throw TtsException("Empty TTS response")
            val stream = body.byteStream()
            try {
                player.play { stream.read(it) }
            } finally {
                call.cancel()
            }
        }
    }

    override fun stop() { player.stop() }
}

/** `POST {endpoint}/audio/speech` with `response_format: pcm` (24 kHz, 16-bit, mono). */
class OpenAICompatibleTTSProvider(
    private val s: FridaySettings,
    private val apiKey: String,
) : PcmHttpTts() {
    override val name = "OpenAI-compatible TTS"
    override fun request(text: String): Request {
        if (apiKey.isBlank()) throw TtsException("TTS API key missing")
        val body = JSONObject().put("model", s.ttsModel).put("voice", s.ttsVoice).put("input", text)
            .put("response_format", "pcm").put("speed", s.ttsSpeed.toDouble().coerceIn(0.25, 4.0))
            .put("instructions", "Calm, clear, intelligent female AI assistant. Natural intonation, slightly futuristic.")
        return Request.Builder().url(s.ttsEndpoint.trimEnd('/') + "/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
}

/** ElevenLabs-style `POST /v1/text-to-speech/{voice}?output_format=pcm_24000` with `xi-api-key`. */
class ElevenLabsCompatibleTTSProvider(
    private val s: FridaySettings,
    private val apiKey: String,
) : PcmHttpTts() {
    override val name = "ElevenLabs-compatible TTS"
    override fun request(text: String): Request {
        if (apiKey.isBlank() || s.ttsVoice.isBlank()) throw TtsException("TTS key or voice id missing")
        val base = s.ttsEndpoint.trimEnd('/').ifBlank { "https://api.elevenlabs.io/v1" }
        val body = JSONObject().put("text", text).put("model_id", s.ttsModel.ifBlank { "eleven_flash_v2_5" })
        return Request.Builder().url("$base/text-to-speech/${s.ttsVoice}?output_format=pcm_24000")
            .header("xi-api-key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
    }
}
