package com.friday.assistant.wake

import kotlinx.coroutines.flow.StateFlow

/**
 * Pluggable wake-word detector.
 * V1 ships [SpeechWakeWordEngine] (platform recognizer loop). A dedicated low-power keyword engine
 * (Porcupine, openWakeWord, ...) implements this same interface and can replace it without touching the rest.
 */
interface WakeWordEngine {
    val running: StateFlow<Boolean>
    /** [remainder] is whatever was said after the wake word in the same breath ("FRIDAY 날씨 알려줘" -> "날씨 알려줘"). */
    fun start(onWake: (remainder: String) -> Unit)
    fun stop()
    /** Temporarily release the microphone (e.g. while the assistant is listening) without forgetting the callback. */
    fun pause()
    fun resume()
}
