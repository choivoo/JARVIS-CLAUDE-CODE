package com.friday.assistant.tts

import kotlinx.coroutines.flow.StateFlow

class TtsException(message: String) : Exception(message)

/** Speaks English text. `speak` suspends until playback ends and must stop promptly on cancel/stop. */
interface TTSProvider {
    val name: String
    /** 0f..1f loudness of what is currently playing, for the HUD. */
    val amplitude: StateFlow<Float>
    suspend fun speak(text: String)
    fun stop()
}
