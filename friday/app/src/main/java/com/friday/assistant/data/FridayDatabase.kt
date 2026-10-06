package com.friday.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, MessageEntity::class, PreferenceEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun dao(): ConversationDao

    companion object {
        fun create(context: Context): FridayDatabase =
            Room.databaseBuilder(context.applicationContext, FridayDatabase::class.java, "friday.db").build()
    }
}
