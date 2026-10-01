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

object Role {
    const val USER = "USER"
    const val JARVIS = "JARVIS"
}
