package com.friday.assistant.data

import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.ai.Role
import kotlinx.coroutines.flow.Flow

/** Conversation memory with a bounded context window; older turns collapse into one summary line. */
class ConversationRepository(
    private val dao: ConversationDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val messages: Flow<List<MessageEntity>> = dao.observeMessages()

    suspend fun addUser(text: String) = add("user", text, "")
    suspend fun addAssistant(speech: String, subtitle: String) = add("assistant", speech, subtitle)

    private suspend fun add(role: String, text: String, subtitle: String) {
        val cid = dao.latestConversationId() ?: dao.insertConversation(ConversationEntity(createdAt = clock()))
        dao.insertMessage(MessageEntity(conversationId = cid, role = role, text = text, subtitle = subtitle, createdAt = clock()))
    }

    /**
     * Context for the AI: the last [maxTurns] messages verbatim (bounded by [maxChars]),
     * and a short local digest of what came before. Nothing else is ever sent.
     */
    suspend fun context(maxTurns: Int = 8, maxChars: Int = 2000): List<ChatMessage> {
        val recent = dao.recentMessages(maxTurns + SUMMARY_SCAN).reversed()
        val older = recent.dropLast(maxTurns.coerceAtMost(recent.size))
        val window = recent.takeLast(maxTurns)
        val out = ArrayList<ChatMessage>()
        val digest = older.filter { it.role == "user" }.joinToString("; ") { it.text.take(40) }
        if (digest.isNotBlank()) out += ChatMessage(Role.SYSTEM, "Earlier the user asked: ${digest.take(300)}")
        var budget = maxChars
        val kept = ArrayList<ChatMessage>()
        for (m in window.asReversed()) {
            val text = m.text.take(500)
            if (text.length > budget) break
            budget -= text.length
            kept += ChatMessage(if (m.role == "user") Role.USER else Role.ASSISTANT, text)
        }
        out += kept.asReversed()
        return out
    }

    suspend fun clear() {
        dao.deleteAllMessages()
        dao.deleteAllConversations()
    }

    suspend fun count() = dao.messageCount()
    suspend fun putPreference(key: String, value: String) = dao.putPreference(PreferenceEntity(key, value))
    suspend fun getPreference(key: String) = dao.getPreference(key)

    private companion object { const val SUMMARY_SCAN = 12 }
}
