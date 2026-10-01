package com.jarvis.assistant.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.data.model.TtsProviderType
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.util.AudioLevelBus
import com.jarvis.assistant.util.JLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** What the controller needs from the voice layer (also the seam used by tests). */
fun interface SpeechOutput {
    /** Suspends until the text has been spoken. */
    @Throws(TtsException::class)
    suspend fun speak(text: String)
}

/**
 * Speaks English text with the configured provider. If a remote voice fails (no key, offline,
 * server error) it silently falls back to the Android system voice so JARVIS never goes mute.
 */
class JarvisSpeaker(
    private val context: Context,
    private val settingsRepo: SettingsRepository,
    private val android: AndroidTTSProvider,
    private val providers: Map<TtsProviderType, TTSProvider>,
    private val player: PcmPlayer,
    val levels: AudioLevelBus,
) : SpeechOutput {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    suspend fun listAndroidVoices(): List<VoiceOption> = android.listVoices()

    /** Suspends until the whole text has been spoken. Throws [TtsException] only if every path failed. */
    override suspend fun speak(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val settings = settingsRepo.current()
        val chunks = splitSentences(clean)
        val focus = requestFocus()
        try {
            coroutineScope {
                var pending: Deferred<PcmAudio?> = async { synthesizeOrNull(chunks[0], settings) }
                for (i in chunks.indices) {
                    val audio = pending.await()
                    if (i + 1 < chunks.size) pending = async { synthesizeOrNull(chunks[i + 1], settings) }
                    if (audio != null) {
                        player.play(audio)
                    } else {
                        fallbackDirect(chunks[i], settings)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { abandonFocus(focus) }
        }
    }

    private suspend fun synthesizeOrNull(text: String, settings: AppSettings): PcmAudio? {
        val voice = voiceSettings(settings, settings.ttsProvider)
        if (settings.ttsProvider != TtsProviderType.ANDROID) {
            try {
                return providers.getValue(settings.ttsProvider).synthesize(text, voice)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtsException) {
                JLog.w("TTS", "${settings.ttsProvider} failed (${e.kind}); using system voice")
            } catch (e: Exception) {
                JLog.w("TTS", "${settings.ttsProvider} failed; using system voice", e)
            }
        }
        return try {
            android.synthesize(text, voiceSettings(settings, TtsProviderType.ANDROID))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JLog.w("TTS", "File synthesis failed; trying direct speech", e)
            null
        }
    }

    private suspend fun fallbackDirect(text: String, settings: AppSettings) {
        try {
            android.speakDirect(text, voiceSettings(settings, TtsProviderType.ANDROID))
        } catch (e: CancellationException) {
            throw e
        } catch (e: TtsException) {
            throw e
        } catch (e: Exception) {
            throw TtsException(TtsError.ENGINE, "Speech output failed")
        }
    }

    private fun voiceSettings(settings: AppSettings, provider: TtsProviderType): VoiceSettings {
        val isSelected = provider == settings.ttsProvider
        return VoiceSettings(
            voice = if (isSelected) settings.ttsVoice else "",
            rate = settings.speechRate,
            pitch = settings.pitch,
            endpoint = if (isSelected) settings.ttsEndpoint else provider.defaultEndpoint,
            model = if (isSelected) settings.ttsModel else provider.defaultModel,
            apiKey = if (provider == TtsProviderType.ANDROID) null else settingsRepo.ttsApiKey(provider),
        )
    }

    private fun requestFocus(): AudioFocusRequest? {
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        return if (audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) request else null
    }

    private fun abandonFocus(request: AudioFocusRequest?) {
        request?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    companion object {
        /** Splits into sentence-sized chunks so playback can begin before the whole reply is synthesised. */
        fun splitSentences(text: String, maxLen: Int = 320): List<String> {
            val parts = Regex("(?<=[.!?])\\s+").split(text).map { it.trim() }.filter { it.isNotEmpty() }
            val chunks = mutableListOf<String>()
            val current = StringBuilder()
            for (part in parts) {
                if (current.isNotEmpty() && current.length + part.length + 1 > maxLen) {
                    chunks += current.toString()
                    current.clear()
                }
                if (part.length > maxLen) {
                    part.chunked(maxLen).forEach { chunks += it }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(part)
                }
            }
            if (current.isNotEmpty()) chunks += current.toString()
            return chunks.ifEmpty { listOf(text) }
        }
    }
}
