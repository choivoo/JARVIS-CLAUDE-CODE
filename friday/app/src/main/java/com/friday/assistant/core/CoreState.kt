package com.friday.assistant.core

/** What the FRIDAY core is doing right now. Drives the HUD animation. */
enum class CoreState { IDLE, LISTENING, THINKING, SPEAKING, EXECUTING, OFFLINE, ERROR }

/** Text currently shown under the core. `user` is what was heard, `subtitle` is FRIDAY's Korean line. */
data class SubtitleState(
    val user: String = "",
    val subtitle: String = "",
    val speaking: Boolean = false,
)
