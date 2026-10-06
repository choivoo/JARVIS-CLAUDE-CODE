package com.jarvis.assistant.data.repository

import com.jarvis.assistant.data.database.ReminderDao
import com.jarvis.assistant.data.database.ReminderEntity
import com.jarvis.assistant.data.database.RoutineDao
import com.jarvis.assistant.data.database.RoutineEntity
import com.jarvis.assistant.data.database.TaskDao
import com.jarvis.assistant.data.database.TaskEntity
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val dao: TaskDao) {
    fun observeAll(): Flow<List<TaskEntity>> = dao.observeAll()

    suspend fun add(list: String, text: String) {
        dao.insert(TaskEntity(listName = normalize(list), text = text.trim().take(300), createdAt = System.currentTimeMillis()))
    }

    suspend fun open(list: String): List<TaskEntity> = dao.open(normalize(list))

    /** Marks the first open task whose text contains [match] (or the n-th open task for "2") as done. */
    suspend fun complete(list: String, match: String): TaskEntity? {
        val open = open(list)
        val index = match.trim().toIntOrNull()
        val target = if (index != null) open.getOrNull(index - 1)
        else open.firstOrNull { it.text.contains(match.trim(), ignoreCase = true) }
        target?.let { dao.setDone(it.id, true) }
        return target
    }

    suspend fun setDone(id: Long, done: Boolean) = dao.setDone(id, done)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clearList(list: String) = dao.clearList(normalize(list))
    suspend fun clearDone() = dao.clearDone()

    companion object {
        /** Spoken names -> stable list keys. */
        fun normalize(raw: String): String = when (raw.trim().lowercase()) {
            "", "todo", "to-do", "task", "tasks", "할일", "할 일" -> "todo"
            "shopping", "grocery", "groceries", "장보기", "쇼핑", "구매" -> "shopping"
            else -> raw.trim().lowercase().take(30)
        }
    }
}

class ReminderRepository(private val dao: ReminderDao) {
    fun observePending(): Flow<List<ReminderEntity>> = dao.observePending()
    suspend fun pending(): List<ReminderEntity> = dao.pending()
    suspend fun get(id: Long): ReminderEntity? = dao.get(id)

    suspend fun add(text: String, triggerAt: Long): ReminderEntity {
        val now = System.currentTimeMillis()
        val id = dao.insert(ReminderEntity(text = text.trim().take(300), triggerAt = triggerAt, createdAt = now))
        return ReminderEntity(id, text.trim().take(300), triggerAt, false, now)
    }

    suspend fun markFired(id: Long) = dao.markFired(id)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun clearPending() = dao.deletePending()
}

class RoutineRepository(private val dao: RoutineDao) {
    fun observeAll(): Flow<List<RoutineEntity>> = dao.observeAll()
    suspend fun all(): List<RoutineEntity> = dao.all()

    suspend fun save(name: String, steps: String) {
        val clean = steps.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        if (name.isBlank() || clean.isEmpty()) return
        val existing = dao.all().firstOrNull { it.name.equals(name.trim(), ignoreCase = true) }
        dao.upsert(RoutineEntity(id = existing?.id ?: 0, name = name.trim().take(40), steps = clean, createdAt = System.currentTimeMillis()))
    }

    suspend fun delete(id: Long) = dao.delete(id)
}
