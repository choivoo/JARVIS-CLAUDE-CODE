package com.jarvis.assistant.ui.conversation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.database.NoteEntity
import com.jarvis.assistant.data.database.ReminderEntity
import com.jarvis.assistant.data.database.TaskEntity
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.theme.hudColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Notes, tasks / shopping lists and reminders, all created by voice and manageable here. */
@Composable
fun NotesScreen(
    notes: List<NoteEntity>,
    onBack: () -> Unit,
    onDelete: (Long) -> Unit,
    onClearAll: () -> Unit,
    tasks: List<TaskEntity> = emptyList(),
    reminders: List<ReminderEntity> = emptyList(),
    onToggleTask: (Long, Boolean) -> Unit = { _, _ -> },
    onDeleteTask: (Long) -> Unit = {},
    onCancelReminder: (Long) -> Unit = {},
) {
    val colors = hudColors()
    var tab by remember { mutableIntStateOf(0) }
    val tabs = listOf("NOTES" to notes.size, "TASKS" to tasks.count { !it.done }, "REMINDERS" to reminders.size)
    Box(Modifier.fillMaxSize()) {
        HudBackground(accent = colors.accent)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
                }
                Text(
                    "LIFE LOG", color = colors.text, fontSize = 16.sp, letterSpacing = 5.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                )
                if (tab == 0) {
                    IconButton(onClick = onClearAll, enabled = notes.isNotEmpty()) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete all notes", tint = colors.textDim)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { i, (name, count) ->
                    val active = i == tab
                    Text(
                        "$name $count",
                        color = if (active) colors.accent else colors.textDim,
                        fontSize = 11.sp, letterSpacing = 2.sp, fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .background(if (active) colors.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable { tab = i }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
            val empty = when (tab) {
                0 -> "\"메모해줘\" 라고 말하면 여기에 저장됩니다"
                1 -> "\"우유를 장보기 목록에 추가해줘\" 처럼 말해 보세요"
                else -> "\"30분 뒤에 빨래 알려줘\" 처럼 말해 보세요"
            }
            val size = when (tab) { 0 -> notes.size; 1 -> tasks.size; else -> reminders.size }
            if (size == 0) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(empty, color = colors.textDim, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (tab) {
                        0 -> items(notes, key = { "n${it.id}" }) { note ->
                            Row2(note.text, stamp(note.createdAt), false, null, { onDelete(note.id) })
                        }
                        1 -> items(tasks, key = { "t${it.id}" }) { t ->
                            Row2(
                                text = t.text, sub = (if (t.listName == "shopping") "SHOPPING · " else "TODO · ") + stamp(t.createdAt),
                                struck = t.done, checked = t.done, onDelete = { onDeleteTask(t.id) },
                                onCheck = { onToggleTask(t.id, !t.done) },
                            )
                        }
                        else -> items(reminders, key = { "r${it.id}" }) { r ->
                            Row2(r.text, "⏰ " + stamp(r.triggerAt), false, null, { onCancelReminder(r.id) })
                        }
                    }
                }
            }
        }
    }
}

private fun stamp(ms: Long) = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun Row2(
    text: String,
    sub: String,
    struck: Boolean,
    checked: Boolean?,
    onDelete: () -> Unit,
    onCheck: () -> Unit = {},
) {
    val colors = hudColors()
    GlassPanel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (checked != null) {
                IconButton(onClick = onCheck) {
                    Icon(
                        Icons.Filled.Check, contentDescription = "Toggle done",
                        tint = if (checked) colors.accent else colors.textDim.copy(alpha = 0.4f),
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text, color = if (struck) colors.textDim else colors.text, fontSize = 14.sp,
                    textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
                )
                Text(sub, color = colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Close, contentDescription = "Delete", tint = colors.textDim)
            }
        }
    }
}
