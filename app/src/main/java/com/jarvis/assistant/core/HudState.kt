package com.jarvis.assistant.core

enum class AssistantPhase(val label: String) {
    IDLE("SYSTEM READY"),
    LISTENING("LISTENING"),
    THINKING("PROCESSING"),
    SPEAKING("SPEAKING"),
    EXECUTING("EXECUTING"),
    ERROR("ERROR"),
}

data class HudState(
    val phase: AssistantPhase = AssistantPhase.IDLE,
    /** True while the wake-word loop owns the microphone (foreground service running). */
    val standby: Boolean = false,
    val online: Boolean = true,
    /** Live transcript of what the user is saying / just said. */
    val partial: String = "",
    /** Korean subtitle for the line JARVIS is speaking. */
    val subtitle: String? = null,
    /** Short Korean hint or error explanation. */
    val notice: String? = null,
) {
    val statusLabel: String
        get() = if (phase == AssistantPhase.IDLE && !online) "OFFLINE" else phase.label
}
