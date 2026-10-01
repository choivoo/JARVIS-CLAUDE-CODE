package com.jarvis.assistant.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, SettingsEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class JarvisDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun settingsDao(): SettingsDao

    companion object {
        fun create(context: Context): JarvisDatabase =
            Room.databaseBuilder(context, JarvisDatabase::class.java, "jarvis.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
