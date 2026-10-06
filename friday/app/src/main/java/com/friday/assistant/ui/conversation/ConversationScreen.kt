package com.friday.assistant.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.assistant.data.MessageEntity
import com.friday.assistant.ui.ConversationViewModel
import com.friday.assistant.ui.components.GlassPanel
import com.friday.assistant.ui.components.HudLabel
import com.friday.assistant.ui.theme.FridayColors
import com.friday.assistant.ui.theme.LocalEnergy

@Composable
fun ConversationScreen(vm: ConversationViewModel) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    ConversationContent(messages, onSend = vm::send, onClear = vm::clear)
}

@Composable
fun ConversationContent(messages: List<MessageEntity>, onSend: (String) -> Unit, onClear: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.lastIndex) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            HudLabel("CONVERSATION")
            IconButton(onClick = { confirm = true }, enabled = messages.isNotEmpty()) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "Delete conversation history", tint = FridayColors.TextDim)
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (messages.isEmpty()) item { Text("대화 기록이 없습니다.", color = FridayColors.TextDim) }
            items(messages, key = { it.id }) { m -> Bubble(m) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, modifier = Modifier.weight(1f),
                placeholder = { Text("메시지 입력…") }, singleLine = true,
            )
            IconButton(onClick = { onSend(text); text = "" }, enabled = text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = LocalEnergy.current.primary)
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("대화 기록 삭제") },
            text = { Text("모든 대화 기록이 영구적으로 삭제됩니다.") },
            confirmButton = { TextButton(onClick = { confirm = false; onClear() }) { Text("삭제") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("취소") } },
        )
    }
}

@Composable
private fun Bubble(m: MessageEntity) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        GlassPanel(Modifier.fillMaxWidth(0.86f)) {
            Column {
                HudLabel(if (user) "YOU" else "FRIDAY", color = if (user) FridayColors.TextDim else LocalEnergy.current.primary)
                if (user) {
                    Text(m.text, color = FridayColors.Text, fontSize = 15.sp)
                } else {
                    Text(m.subtitle.ifBlank { m.text }, color = FridayColors.Text, fontSize = 15.sp)
                    if (m.subtitle.isNotBlank()) Text(m.text, color = FridayColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
