package com.friday.assistant.ai

enum class Role { SYSTEM, USER, ASSISTANT }

data class ChatMessage(val role: Role, val content: String)

/** Typed failures so the UI can explain what went wrong. */
sealed class AiException(message: String) : Exception(message) {
    class NotConfigured(message: String = "AI is not configured") : AiException(message)
    class NoInternet : AiException("No internet connection")
    class InvalidKey : AiException("Invalid API key")
    class RateLimited : AiException("Rate limit reached")
    class Timeout : AiException("The AI request timed out")
    class Server(val code: Int) : AiException("AI server error $code")
    class BadResponse(message: String) : AiException(message)
}

/** Vendor-neutral chat completion. Implementations must not log keys or prompts. */
interface AIProvider {
    val name: String
    /** Returns the raw model text (expected to be the FRIDAY JSON). */
    suspend fun complete(messages: List<ChatMessage>): String
}
