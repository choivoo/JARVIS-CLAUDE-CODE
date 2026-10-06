package com.jarvis.assistant.tts

import com.jarvis.assistant.data.model.TtsProviderType

/** 16-bit little-endian PCM. */
class PcmAudio(val data: ByteArray, val sampleRate: Int, val channels: Int = 1) {
    val frames: Int get() = data.size / (2 * channels)
}

data class VoiceSettings(
    val voice: String,
    val rate: Float,
    val pitch: Float,
    val endpoint: String,
    val model: String,
    val apiKey: String?,
)

enum class TtsError { NOT_CONFIGURED, NETWORK, ENGINE, EMPTY }

class TtsException(val kind: TtsError, message: String) : Exception(message)

/**
 * Voice abstraction. Every provider returns raw PCM so that the same player can render the audio
 * and derive a real amplitude envelope for the HUD waveform.
 */
interface TTSProvider {
    val type: TtsProviderType

    @Throws(TtsException::class)
    suspend fun synthesize(text: String, settings: VoiceSettings): PcmAudio
}
