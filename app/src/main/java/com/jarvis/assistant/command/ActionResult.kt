package com.jarvis.assistant.command

/**
 * Outcome of a command. [speech]/[subtitle] (English/Korean) are spoken after execution when present.
 * When [narrate] is true and [data] is set, the AI is asked to phrase the result; [speech]/[subtitle]
 * then act as the fallback if the AI is unreachable.
 */
data class ActionResult(
    val success: Boolean,
    val speech: String? = null,
    val subtitle: String? = null,
    val data: String? = null,
    val narrate: Boolean = false,
) {
    companion object {
        fun ok(speech: String? = null, subtitle: String? = null) = ActionResult(true, speech, subtitle)

        fun fail(speech: String, subtitle: String) = ActionResult(false, speech, subtitle)

        fun unsupported() = fail(
            "I'm afraid I can't do that yet.",
            "아직 지원하지 않는 명령입니다.",
        )
    }
}
