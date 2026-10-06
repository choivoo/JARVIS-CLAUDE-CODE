package com.jarvis.assistant.data.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "USER" or "JARVIS". */
    val role: String,
    /** What the user said, or the English line JARVIS spoke. */
    val text: String,
    /** Korean subtitle shown while JARVIS spoke. */
    val subtitle: String? = null,
    /** JSON of the executed action, if any. */
    val actionJson: String? = null,
    val createdAt: Long,
)

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val key: String,
    val value: String,
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val createdAt: Long,
)

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** "todo", "shopping", or any list name. */
    val listName: String,
    val text: String,
    val done: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "reminders")
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val triggerAt: Long,
    val fired: Boolean = false,
    val createdAt: Long,
)

@Entity(tableName = "routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** One natural-language command per line. */
    val steps: String,
    val createdAt: Long,
)

object Role {
    const val USER = "USER"
    const val JARVIS = "JARVIS"
}
