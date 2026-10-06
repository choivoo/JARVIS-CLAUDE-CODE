package com.friday.assistant.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdAt: Long,
)

@Entity(tableName = "messages", indices = [Index("conversationId")])
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" or "assistant". */
    val role: String,
    /** What was said: Korean for the user, English speech for FRIDAY. */
    val text: String,
    /** Korean subtitle for assistant messages. */
    val subtitle: String = "",
    val createdAt: Long,
)

@Entity(tableName = "preferences")
data class PreferenceEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Dao
interface ConversationDao {
    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Insert suspend fun insertMessage(m: MessageEntity): Long
    @Query("SELECT id FROM conversations ORDER BY id DESC LIMIT 1") suspend fun latestConversationId(): Long?
    @Query("SELECT * FROM messages ORDER BY id DESC LIMIT :limit") suspend fun recentMessages(limit: Int): List<MessageEntity>
    @Query("SELECT * FROM messages ORDER BY id ASC") fun observeMessages(): Flow<List<MessageEntity>>
    @Query("SELECT COUNT(*) FROM messages") suspend fun messageCount(): Int
    @Query("DELETE FROM messages") suspend fun deleteAllMessages()
    @Query("DELETE FROM conversations") suspend fun deleteAllConversations()
    @Query("DELETE FROM preferences WHERE `key` = :key") suspend fun deletePreference(key: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putPreference(p: PreferenceEntity)
    @Query("SELECT value FROM preferences WHERE `key` = :key") suspend fun getPreference(key: String): String?
}
