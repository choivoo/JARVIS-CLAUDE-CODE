package com.friday.assistant.tts

import com.friday.assistant.settings.TtsProviderType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/** Speaks text; implemented by [FridaySpeaker] and faked in tests. */
interface Speaker {
    val amplitude: StateFlow<Float>
    suspend fun speak(text: String)
    fun stop()
}

/** Prefers the configured cloud voice, falls back to Android TTS on any failure. */
class FridaySpeaker(
    private val configured: () -> TtsProviderType,
    private val cloud: (TtsProviderType) -> TTSProvider?,
    private val fallback: TTSProvider,
) : Speaker {
    @Volatile private var current: TTSProvider = fallback
    override val amplitude: StateFlow<Float> get() = current.amplitude

    override suspend fun speak(text: String) {
        val type = configured()
        if (type != TtsProviderType.ANDROID) {
            val provider = runCatching { cloud(type) }.getOrNull()
            if (provider != null) {
                current = provider
                try {
                    provider.speak(text)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // fall through to the on-device voice
                }
            }
        }
        current = fallback
        fallback.speak(text)
    }

    override fun stop() {
        current.stop()
        fallback.stop()
    }
}
