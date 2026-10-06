package com.friday.assistant.tts

import com.friday.assistant.voice.Emphasis
import kotlinx.coroutines.flow.StateFlow

class TtsException(message: String) : Exception(message)

/** Speaks English text. `speak` suspends until playback ends and must stop promptly on cancel/stop. */
interface TTSProvider {
    val name: String
    /** 0f..1f loudness of what is currently playing, for the HUD. */
    val amplitude: StateFlow<Float>
    /** [onFirstAudio] fires when sound actually starts (used for latency measurement). */
    suspend fun speak(text: String, emphasis: Emphasis = Emphasis.NORMAL, onFirstAudio: () -> Unit = {})
    fun stop()
}
