package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.database.NoteDao
import com.jarvis.assistant.data.database.NoteEntity
import kotlinx.coroutines.flow.Flow

class NoteRepository(private val dao: NoteDao) {
    fun observeAll(): Flow<List<NoteEntity>> = dao.observeAll()

    suspend fun add(text: String) {
        dao.insert(NoteEntity(text = text.trim().take(2_000), createdAt = System.currentTimeMillis()))
    }

    suspend fun latest(limit: Int = 5): List<NoteEntity> = dao.latest(limit)

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clear() = dao.deleteAll()
}
