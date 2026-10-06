package com.jarvis.assistant.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single 0..1 audio level shared by the microphone (while listening) and the speaker
 * (while JARVIS talks). The HUD reads it inside the draw phase so no recomposition happens.
 */
class AudioLevelBus {
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    fun set(value: Float) {
        _level.value = value.coerceIn(0f, 1f)
    }
}
