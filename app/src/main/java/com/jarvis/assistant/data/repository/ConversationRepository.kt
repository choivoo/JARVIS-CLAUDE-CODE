package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.database.ConversationDao
import com.jarvis.assistant.data.database.ConversationEntity
import com.jarvis.assistant.data.database.MessageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ConversationRepository(private val dao: ConversationDao) {
    private val mutex = Mutex()

    fun observeRecent(limit: Int = 50): Flow<List<MessageEntity>> = dao.observeRecent(limit)

    /** Re-uses the latest conversation unless it has been idle for a while. */
    private suspend fun activeConversationId(now: Long): Long {
        val latest = dao.latestConversation()
        if (latest != null && now - latest.updatedAt < SESSION_GAP_MS) return latest.id
        return dao.insertConversation(ConversationEntity(title = "Session", createdAt = now, updatedAt = now))
    }

    suspend fun addMessage(role: String, text: String, subtitle: String? = null, actionJson: String? = null) {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val id = activeConversationId(now)
            dao.insertMessage(
                MessageEntity(
                    conversationId = id,
                    role = role,
                    text = text,
                    subtitle = subtitle,
                    actionJson = actionJson,
                    createdAt = now,
                ),
            )
            dao.touch(id, now)
        }
    }

    /** Oldest first, ready to be replayed to the AI as context. */
    suspend fun recentContext(limit: Int = 8): List<MessageEntity> {
        val latest = dao.latestConversation() ?: return emptyList()
        if (System.currentTimeMillis() - latest.updatedAt >= SESSION_GAP_MS) return emptyList()
        return dao.recentForConversation(latest.id, limit).reversed()
    }

    suspend fun clearAll() = mutex.withLock { dao.deleteAll() }

    private companion object {
        const val SESSION_GAP_MS = 6 * 60 * 60 * 1000L
    }
}
