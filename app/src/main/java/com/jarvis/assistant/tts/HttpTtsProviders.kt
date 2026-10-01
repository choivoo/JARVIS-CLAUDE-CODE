package com.jarvis.assistant.tts

import com.jarvis.assistant.data.model.TtsProviderType
import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

private suspend fun fetchAudio(request: Request): ByteArray = withContext(Dispatchers.IO) {
    try {
        Http.client.await(request).use { response ->
            if (!response.isSuccessful) {
                val kind = if (response.code == 401 || response.code == 403) TtsError.NOT_CONFIGURED else TtsError.ENGINE
                throw TtsException(kind, "TTS service returned HTTP ${response.code}")
            }
            response.body?.bytes() ?: throw TtsException(TtsError.EMPTY, "Empty audio")
        }
    } catch (e: IOException) {
        throw TtsException(TtsError.NETWORK, "TTS network error: ${e.javaClass.simpleName}")
    }
}

/** POST {endpoint}/audio/speech - OpenAI and compatible servers. Requests WAV so we can decode it directly. */
class OpenAICompatibleTTSProvider : TTSProvider {
    override val type = TtsProviderType.OPENAI_COMPATIBLE

    override suspend fun synthesize(text: String, settings: VoiceSettings): PcmAudio {
        val key = settings.apiKey?.trim().orEmpty()
        if (key.isEmpty() && settings.endpoint.contains("api.openai.com")) {
            throw TtsException(TtsError.NOT_CONFIGURED, "TTS API key missing")
        }
        val payload = JSONObject()
            .put("model", settings.model)
            .put("input", text)
            .put("voice", settings.voice)
            .put("response_format", "wav")
            .put("speed", settings.rate.coerceIn(0.25f, 4.0f).toDouble())
        val builder = Request.Builder()
            .url(settings.endpoint.trimEnd('/') + "/audio/speech")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
        return WavParser.parse(fetchAudio(builder.build()))
    }
}

/** ElevenLabs text-to-speech, requesting raw 24 kHz PCM. */
class ElevenLabsTTSProvider : TTSProvider {
    override val type = TtsProviderType.ELEVENLABS

    override suspend fun synthesize(text: String, settings: VoiceSettings): PcmAudio {
        val key = settings.apiKey?.trim().orEmpty()
        if (key.isEmpty()) throw TtsException(TtsError.NOT_CONFIGURED, "TTS API key missing")
        val payload = JSONObject()
            .put("text", text)
            .put("model_id", settings.model)
            .put(
                "voice_settings",
                JSONObject().put("stability", 0.6).put("similarity_boost", 0.75).put("style", 0.1),
            )
        val request = Request.Builder()
            .url("${settings.endpoint.trimEnd('/')}/text-to-speech/${settings.voice}?output_format=pcm_24000")
            .header("xi-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val bytes = fetchAudio(request)
        if (bytes.size < 2) throw TtsException(TtsError.EMPTY, "Empty audio")
        return PcmAudio(bytes.copyOf(bytes.size - bytes.size % 2), 24_000, 1)
    }
}
