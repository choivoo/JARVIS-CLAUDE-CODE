package com.jarvis.assistant.ui.conversation

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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.database.NoteEntity
import com.jarvis.assistant.ui.components.GlassPanel
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.theme.hudColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private notes saved by voice ("메모해줘"). [notes] is newest first. */
@Composable
fun NotesScreen(notes: List<NoteEntity>, onBack: () -> Unit, onDelete: (Long) -> Unit, onClearAll: () -> Unit) {
    val colors = hudColors()
    Box(Modifier.fillMaxSize()) {
        HudBackground(accent = colors.accent)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
                }
                Text(
                    "NOTES", color = colors.text, fontSize = 16.sp, letterSpacing = 5.sp,
                    fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClearAll, enabled = notes.isNotEmpty()) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete all notes", tint = colors.textDim)
                }
            }
            if (notes.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("\"메모해줘\" 라고 말하면 여기에 저장됩니다", color = colors.textDim, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(notes, key = { it.id }) { note ->
                        GlassPanel(Modifier.fillMaxWidth()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(note.text, color = colors.text, fontSize = 14.sp)
                                    Text(
                                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(note.createdAt)),
                                        color = colors.textDim, fontSize = 10.sp, fontFamily = FontFamily.Monospace,
                                    )
                                }
                                IconButton(onClick = { onDelete(note.id) }) {
                                    Icon(Icons.Filled.Close, contentDescription = "Delete note", tint = colors.textDim)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
