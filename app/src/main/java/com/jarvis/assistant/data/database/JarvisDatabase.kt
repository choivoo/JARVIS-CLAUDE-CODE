package com.jarvis.assistant.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, SettingsEntity::class, NoteEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun settingsDao(): SettingsDao
    abstract fun noteDao(): NoteDao

    companion object {
        /** v1.0 -> v1.1: adds notes. Existing conversations and settings are preserved. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `notes` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`text` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL)",
                )
            }
        }

        fun create(context: Context): JarvisDatabase =
            Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
