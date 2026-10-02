package com.jarvis.assistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.jarvis.assistant.data.database.JarvisDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Proves a v1.0 database (conversations + settings) survives the upgrade to v1.1 untouched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MigrationTest {
    @Test
    fun upgradeKeepsConversationsAndSettingsAndAddsNotes() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val file = ctx.getDatabasePath("jarvis-migration.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE `conversations` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `title` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE `messages` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `conversationId` INTEGER NOT NULL, `role` TEXT NOT NULL, `text` TEXT NOT NULL, `subtitle` TEXT, `actionJson` TEXT, `createdAt` INTEGER NOT NULL, FOREIGN KEY(`conversationId`) REFERENCES `conversations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
            db.execSQL("CREATE INDEX `index_messages_conversationId` ON `messages` (`conversationId`)")
            db.execSQL("CREATE TABLE `settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            db.execSQL("INSERT INTO conversations VALUES (1, 'Session', 1, 2)")
            db.execSQL("INSERT INTO messages VALUES (1, 1, 'USER', 'hello', NULL, NULL, 1)")
            db.execSQL("INSERT INTO settings VALUES ('ui.theme', 'LIGHT')")
            db.version = 1
        }
        val room = Room.databaseBuilder(ctx, JarvisDatabase::class.java, "jarvis-migration.db")
            .addMigrations(JarvisDatabase.MIGRATION_1_2, JarvisDatabase.MIGRATION_2_3)
            .allowMainThreadQueries()
            .build()
        try {
            assertEquals("LIGHT", room.settingsDao().getAll().single().value)
            assertEquals("hello", room.conversationDao().recentForConversation(1, 10).single().text)
            room.noteDao().insert(com.jarvis.assistant.data.database.NoteEntity(text = "milk", createdAt = 5))
            assertEquals(listOf("milk"), room.noteDao().latest(5).map { it.text })
            room.taskDao().insert(com.jarvis.assistant.data.database.TaskEntity(listName = "shopping", text = "eggs", createdAt = 1))
            assertEquals(listOf("eggs"), room.taskDao().open("shopping").map { it.text })
            val id = room.reminderDao().insert(com.jarvis.assistant.data.database.ReminderEntity(text = "call", triggerAt = 10, createdAt = 1))
            assertEquals("call", room.reminderDao().get(id)?.text)
            room.routineDao().upsert(com.jarvis.assistant.data.database.RoutineEntity(name = "x", steps = "a\nb", createdAt = 1))
            assertEquals(1, room.routineDao().all().size)
        } finally {
            room.close()
        }
    }
}
