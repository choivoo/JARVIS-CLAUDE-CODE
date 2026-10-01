package com.jarvis.assistant.data.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Insert
    suspend fun insertConversation(conversation: ConversationEntity): Long

    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC LIMIT 1")
    suspend fun latestConversation(): ConversationEntity?

    @Query("UPDATE conversations SET updatedAt = :time WHERE id = :id")
    suspend fun touch(id: Long, time: Long)

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("SELECT * FROM messages ORDER BY createdAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentForConversation(conversationId: Long, limit: Int): List<MessageEntity>

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings")
    fun observeAll(): Flow<List<SettingsEntity>>

    @Query("SELECT * FROM settings")
    suspend fun getAll(): List<SettingsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: SettingsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(entities: List<SettingsEntity>)
}

@Dao
interface NoteDao {
    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Query("SELECT * FROM notes ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<NoteEntity>

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM notes")
    suspend fun deleteAll()
}
