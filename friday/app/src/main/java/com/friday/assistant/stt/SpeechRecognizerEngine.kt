package com.friday.assistant.stt

import kotlinx.coroutines.flow.StateFlow

enum class SttState { IDLE, LISTENING, PROCESSING, ERROR }

enum class SttError { NO_PERMISSION, NO_MATCH, TIMEOUT, NETWORK, BUSY, UNAVAILABLE, OTHER }

sealed class SttResult {
    data class Text(val text: String) : SttResult()
    /** Nothing was said (empty speech or timeout). */
    data object Empty : SttResult()
    data class Failure(val error: SttError) : SttResult()
}

/** Replaceable speech-to-text. Default is Android SpeechRecognizer; Whisper / cloud engines can plug in here. */
interface SpeechRecognizerEngine {
    val state: StateFlow<SttState>
    /** Live partial transcript while listening. */
    val partial: StateFlow<String>
    /** Microphone loudness 0f..1f while listening, for the HUD waveform. */
    val level: StateFlow<Float>
    suspend fun listenOnce(): SttResult
    fun cancel()
}
