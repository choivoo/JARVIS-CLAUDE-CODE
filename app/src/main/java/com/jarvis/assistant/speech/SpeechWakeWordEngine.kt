package com.jarvis.assistant.speech

import android.content.Context
import com.jarvis.assistant.data.model.WakeSensitivity
import com.jarvis.assistant.util.JLog
import com.jarvis.assistant.util.Perms
import kotlinx.coroutines.delay

/**
 * Default wake-word engine: short recognition windows (on-device and offline-preferred when the
 * device supports it, which is both cheaper and beep-free), matched against "JARVIS" / "자비스".
 * Failure handling uses back-off so a broken recognizer never spins the CPU.
 */
class SpeechWakeWordEngine(
    private val context: Context,
    private val recognizer: SpeechRecognizerEngine,
) : WakeWordEngine {
    override val name = "SpeechRecognizer wake word"

    override suspend fun awaitWake(languageTag: String, sensitivity: WakeSensitivity): WakeDetection {
        if (!Perms.hasMic(context)) throw WakeWordException(WakeError.PERMISSION, "Microphone permission missing")
        if (!recognizer.isAvailable()) throw WakeWordException(WakeError.UNAVAILABLE, "No speech recognition service")

        var backoff = 300L
        var consecutiveFailures = 0
        while (true) {
            val result = recognizer.listen(
                ListenRequest(
                    languageTag = languageTag,
                    preferOffline = true,
                    preferOnDevice = true,
                    maxDurationMs = 8_000,
                    silenceMs = 650,
                    // "JARVIS" is an English word: let a Korean recognizer also hear en-US.
                    additionalLanguages = if (languageTag.startsWith("en")) emptyList() else listOf("en-US"),
                ),
            )
            when (result) {
                is SttResult.Text -> {
                    consecutiveFailures = 0
                    backoff = 300L
                    WakeWordMatcher.match(result.alternatives, sensitivity)?.let { return it }
                }
                SttResult.NoSpeech -> {
                    consecutiveFailures = 0
                    backoff = 300L
                }
                is SttResult.Failure -> {
                    when (result.error) {
                        SttError.PERMISSION ->
                            throw WakeWordException(WakeError.PERMISSION, "Microphone permission missing")
                        SttError.UNAVAILABLE ->
                            throw WakeWordException(WakeError.UNAVAILABLE, "Speech recognition unavailable")
                        else -> {
                            consecutiveFailures++
                            if (consecutiveFailures >= 8) {
                                throw WakeWordException(WakeError.TRANSIENT, "Speech recognition keeps failing")
                            }
                            JLog.d("WakeWord", "recognizer failure ${result.error} code=${result.code}")
                            delay(backoff)
                            backoff = (backoff * 2).coerceAtMost(5_000)
                        }
                    }
                }
            }
            // Tiny pause between windows so the recognition service can recycle.
            delay(120)
        }
    }
}
