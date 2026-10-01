package com.jarvis.assistant.ai

import com.jarvis.assistant.data.model.AiProviderType

data class ChatTurn(val role: String, val content: String) {
    companion object {
        const val SYSTEM = "system"
        const val USER = "user"
        const val ASSISTANT = "assistant"
    }
}

data class AiConfig(
    val provider: AiProviderType,
    val endpoint: String,
    val model: String,
    val apiKey: String?,
)

enum class AiError { NOT_CONFIGURED, NETWORK, TIMEOUT, AUTH, RATE_LIMIT, SERVER, BAD_RESPONSE }

class AiException(val kind: AiError, message: String) : Exception(message)

/**
 * Brain abstraction. Implementations return the raw model text (expected to be the JSON reply
 * described in the system prompt); parsing is done by [AiReplyParser].
 */
interface AIProvider {
    val type: AiProviderType

    @Throws(AiException::class)
    suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String
}
