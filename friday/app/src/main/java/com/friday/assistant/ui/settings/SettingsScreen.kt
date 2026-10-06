package com.friday.assistant.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.friday.assistant.settings.AccentTheme
import com.friday.assistant.settings.AiProviderType
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.settings.TtsProviderType
import com.friday.assistant.ui.SettingsViewModel
import com.friday.assistant.ui.components.GlassPanel
import com.friday.assistant.ui.components.SectionTitle
import com.friday.assistant.ui.theme.FridayColors

/** Callbacks that need an Activity (permission dialogs, starting the service). */
class SettingsActions(
    val setBackground: (Boolean) -> Unit,
    val requestPermission: (String) -> Unit,
    val isGranted: (String) -> Boolean,
)

@Composable
fun SettingsScreen(vm: SettingsViewModel, actions: SettingsActions) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keyState.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    SettingsContent(
        s, keys.ai, keys.tts, voices, msg, actions,
        update = vm::update, saveAiKey = vm::saveAiKey, saveTtsKey = vm::saveTtsKey,
        loadVoices = vm::loadVoices, preview = vm::previewVoice,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsContent(
    s: FridaySettings, hasAiKey: Boolean, hasTtsKey: Boolean, voices: List<String>, message: String?,
    actions: SettingsActions,
    update: ((FridaySettings) -> FridaySettings) -> Unit,
    saveAiKey: (String) -> Unit, saveTtsKey: (String) -> Unit,
    loadVoices: () -> Unit, preview: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        message?.let { Text(it, color = FridayColors.Error, fontSize = 13.sp) }

        SectionTitle("AI Core")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AiProviderType.entries.forEach { p ->
                FilterChip(
                    selected = s.aiProvider == p, label = { Text(p.label) },
                    onClick = { update { it.copy(aiProvider = p, aiModel = p.defaultModel, aiEndpoint = p.defaultEndpoint) } },
                )
            }
        }
        TextRow("AI Model", s.aiModel) { v -> update { it.copy(aiModel = v) } }
        TextRow("Endpoint", s.aiEndpoint) { v -> update { it.copy(aiEndpoint = v) } }
        if (s.aiProvider.needsKey) KeyRow("API Key", hasAiKey, saveAiKey)

        SectionTitle("Voice (TTS)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TtsProviderType.entries.forEach { p ->
                FilterChip(selected = s.ttsProvider == p, label = { Text(p.label) }, onClick = { update { it.copy(ttsProvider = p) } })
            }
        }
        if (s.ttsProvider != TtsProviderType.ANDROID) {
            TextRow("TTS Endpoint", s.ttsEndpoint) { v -> update { it.copy(ttsEndpoint = v) } }
            TextRow("TTS Model", s.ttsModel) { v -> update { it.copy(ttsModel = v) } }
            TextRow("Voice / Voice ID", s.ttsVoice) { v -> update { it.copy(ttsVoice = v) } }
            KeyRow("TTS API Key", hasTtsKey, saveTtsKey)
        } else {
            Text("Android voice: ${s.androidVoice.ifBlank { "auto (English, female-leaning)" }}", color = FridayColors.TextDim, fontSize = 13.sp)
            OutlinedButton(onClick = loadVoices) { Text("LIST ENGLISH VOICES") }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                voices.take(24).forEach { v ->
                    FilterChip(selected = s.androidVoice == v, label = { Text(v, fontSize = 11.sp) }, onClick = { update { it.copy(androidVoice = v) } })
                }
            }
        }
        LabeledSlider("Speed ${"%.2f".format(s.ttsSpeed)}", s.ttsSpeed, 0.6f..1.6f) { v -> update { it.copy(ttsSpeed = v) } }
        LabeledSlider("Pitch ${"%.2f".format(s.ttsPitch)}", s.ttsPitch, 0.7f..1.5f) { v -> update { it.copy(ttsPitch = v) } }
        OutlinedButton(onClick = preview) { Text("PREVIEW VOICE") }

        SectionTitle("Listening")
        Text("STT Engine: Android SpeechRecognizer (ko-KR)", color = FridayColors.TextDim, fontSize = 13.sp)
        Text("Wake Word: ${s.wakeWord}", color = FridayColors.TextDim, fontSize = 13.sp)
        SwitchRow("Background Assistant", "Foreground service with a visible notification", s.backgroundAssistant) { actions.setBackground(it) }
        SwitchRow("Auto Listen", "Listen again after FRIDAY asks a question", s.autoListen) { v -> update { it.copy(autoListen = v) } }
        SwitchRow("Voice Feedback", "Speak replies aloud", s.voiceFeedback) { v -> update { it.copy(voiceFeedback = v) } }

        SectionTitle("Weather")
        TextRow("Default City", s.defaultCity) { v -> update { it.copy(defaultCity = v) } }

        SectionTitle("Memory")
        SwitchRow("Conversation Memory", "Store chats on this device and use recent context", s.memoryEnabled) { v -> update { it.copy(memoryEnabled = v) } }

        SectionTitle("Appearance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentTheme.entries.forEach { a ->
                FilterChip(selected = s.accent == a, label = { Text(a.label) }, onClick = { update { it.copy(accent = a) } })
            }
        }
        SwitchRow("AMOLED Mode", "Pure black background", s.amoled) { v -> update { it.copy(amoled = v) } }
        SwitchRow("Developer Diagnostics", "Show the Diagnostics tab", s.developerDiagnostics) { v -> update { it.copy(developerDiagnostics = v) } }

        SectionTitle("Permissions")
        com.friday.assistant.util.Permissions.required.plus(com.friday.assistant.util.Permissions.optional).forEach { (perm, why) ->
            val ok = actions.isGranted(perm)
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(perm.substringAfterLast('.'), fontSize = 13.sp, color = FridayColors.Text)
                    Text(why, fontSize = 11.sp, color = FridayColors.TextDim)
                }
                if (ok) Text("GRANTED", color = FridayColors.Ok, fontSize = 11.sp)
                else OutlinedButton(onClick = { actions.requestPermission(perm) }) { Text("ALLOW") }
            }
        }
        Text("Keys are stored encrypted with the Android Keystore and never leave this device except to your chosen provider.", color = FridayColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun TextRow(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    OutlinedTextField(
        value = text, onValueChange = { text = it; onChange(it) }, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
}

@Composable
private fun KeyRow(label: String, saved: Boolean, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    GlassPanel(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Column {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text(if (saved) "Saved securely (hidden)" else "Not set") },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(text); text = "" }, enabled = text.isNotBlank()) { Text("SAVE") }
                OutlinedButton(onClick = { onSave("") }, enabled = saved) { Text("REMOVE") }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FridayColors.Text, fontSize = 15.sp)
            Text(sub, color = FridayColors.TextDim, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, color = FridayColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
