package com.jarvis.assistant.ai

import org.json.JSONObject

/** A command the AI asks JARVIS to run. Only types registered in the CommandRouter can execute. */
data class AiAction(val type: String, val params: Map<String, String> = emptyMap()) {
    fun param(name: String): String? = params[name]?.trim()?.takeIf { it.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        params.forEach { (k, v) -> put(k, v) }
    }
}

data class AiReply(
    /** English sentence(s) spoken aloud. */
    val speech: String,
    /** Korean subtitle shown on screen. */
    val subtitle: String,
    val action: AiAction? = null,
    /** False when the model did not return valid JSON and a fallback was synthesised. */
    val wellFormed: Boolean = true,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("speech", speech)
        put("subtitle", subtitle)
        put("action", action?.toJson() ?: JSONObject.NULL)
    }
}
