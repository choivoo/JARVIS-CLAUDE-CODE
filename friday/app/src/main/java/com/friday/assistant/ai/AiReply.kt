package com.friday.assistant.ai

/** One action the AI asks FRIDAY to perform. Only allow-listed types are ever executed. */
data class AiAction(val type: String, val params: Map<String, String> = emptyMap()) {
    val target: String? get() = params["target"]
}

/** Structured AI answer: English `speech`, Korean `subtitle`, optional `action`. */
data class AiReply(val speech: String, val subtitle: String, val action: AiAction? = null)
