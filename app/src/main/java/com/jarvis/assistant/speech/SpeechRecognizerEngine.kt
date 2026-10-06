package com.jarvis.assistant.speech

data class ListenRequest(
    val languageTag: String = "ko-KR",
    val preferOffline: Boolean = false,
    /** Try the on-device recognizer first (Android 12+). Falls back automatically. */
    val preferOnDevice: Boolean = false,
    val maxDurationMs: Long = 20_000,
    val silenceMs: Long = 1_200,
    /** Extra languages the recognizer may hear (e.g. English words inside Korean speech). */
    val additionalLanguages: List<String> = emptyList(),
)

class ListenCallbacks(
    val onReady: () -> Unit = {},
    val onPartial: (String) -> Unit = {},
    /** Normalised 0..1 input level. */
    val onLevel: (Float) -> Unit = {},
)

enum class SttError { PERMISSION, NETWORK, BUSY, UNAVAILABLE, TIMEOUT, OTHER }

sealed interface SttResult {
    /** [alternatives] includes [text] first, then lower-ranked hypotheses. */
    data class Text(val text: String, val alternatives: List<String>) : SttResult
    data object NoSpeech : SttResult
    data class Failure(val error: SttError, val code: Int = -1) : SttResult
}

/**
 * Speech-to-text abstraction. [AndroidSpeechRecognizer] is the default; a Whisper (on-device or
 * HTTP) implementation only needs to implement this interface and be registered in AppContainer.
 */
interface SpeechRecognizerEngine {
    fun isAvailable(): Boolean

    /** Listens for one utterance. Cancelling the coroutine stops the recognizer immediately. */
    suspend fun listen(request: ListenRequest, callbacks: ListenCallbacks = ListenCallbacks()): SttResult
}
