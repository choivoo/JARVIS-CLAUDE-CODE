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

    @Query("SELECT value FROM settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

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

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Query("SELECT * FROM tasks ORDER BY done ASC, createdAt DESC, id DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE listName = :list AND done = 0 ORDER BY createdAt ASC, id ASC")
    suspend fun open(list: String): List<TaskEntity>

    @Query("UPDATE tasks SET done = :done WHERE id = :id")
    suspend fun setDone(id: Long, done: Boolean)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM tasks WHERE listName = :list")
    suspend fun clearList(list: String)

    @Query("DELETE FROM tasks WHERE done = 1")
    suspend fun clearDone()
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insert(reminder: ReminderEntity): Long

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun get(id: Long): ReminderEntity?

    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY triggerAt ASC")
    suspend fun pending(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE fired = 0 ORDER BY triggerAt ASC")
    fun observePending(): Flow<List<ReminderEntity>>

    @Query("UPDATE reminders SET fired = 1 WHERE id = :id")
    suspend fun markFired(id: Long)

    @Query("DELETE FROM reminders WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM reminders WHERE fired = 0")
    suspend fun deletePending()
}

@Dao
interface RoutineDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(routine: RoutineEntity): Long

    @Query("SELECT * FROM routines ORDER BY name ASC")
    fun observeAll(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM routines ORDER BY name ASC")
    suspend fun all(): List<RoutineEntity>

    @Query("DELETE FROM routines WHERE id = :id")
    suspend fun delete(id: Long)
}
