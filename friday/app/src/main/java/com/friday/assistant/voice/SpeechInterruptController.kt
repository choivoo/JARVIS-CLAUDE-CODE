package com.friday.assistant.voice

import com.friday.assistant.wake.WakeWordMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Barge-in logic: tracks what FRIDAY is saying and decides whether a heard "FRIDAY" is the user interrupting
 * or just FRIDAY's own voice (e.g. when it says the weekday "Friday").
 */
class SpeechInterruptController {
    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()
    @Volatile var currentText: String = ""
        private set
    var interruptions = 0
        private set
    @Volatile private var stopper: (() -> Unit)? = null

    fun attach(stop: () -> Unit) { stopper = stop }

    fun onSpeechStart(text: String) { currentText = text; _speaking.value = true }
    fun onSpeechEnd() { currentText = ""; _speaking.value = false }

    /** True when [transcript] contains the wake word and cannot be an echo of our own speech. */
    fun isBargeIn(transcript: String): Boolean {
        if (!_speaking.value || WakeWordMatcher.match(transcript) == null) return false
        return !echoesWakeWord()
    }

    /** True if the line being spoken itself contains the wake word, so a heard "Friday" is ambiguous. */
    fun echoesWakeWord(): Boolean = WakeWordMatcher.match(currentText) != null

    fun interrupt() { interruptions++; stopper?.invoke() }
}
