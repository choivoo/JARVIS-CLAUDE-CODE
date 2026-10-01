package com.jarvis.assistant.speech

/** [trailingText] is whatever the user said after the wake word in the same breath, if anything. */
data class WakeDetection(val phrase: String, val trailingText: String?)

enum class WakeError { PERMISSION, UNAVAILABLE, TRANSIENT }

class WakeWordException(val kind: WakeError, message: String) : Exception(message)

/**
 * Wake-word abstraction. [awaitWake] suspends until the wake word is heard; cancelling the
 * coroutine releases the microphone. A dedicated low-power detector (Porcupine, openWakeWord,
 * Vosk grammar mode, ...) can replace [SpeechWakeWordEngine] by implementing this interface.
 */
interface WakeWordEngine {
    val name: String

    @Throws(WakeWordException::class)
    suspend fun awaitWake(languageTag: String): WakeDetection
}
