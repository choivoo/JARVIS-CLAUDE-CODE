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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.Role
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.friday.assistant.permission.PermItem
import com.friday.assistant.permission.PermKind
import com.friday.assistant.permission.PermStatus
import com.friday.assistant.settings.AudioFocusMode
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
    val openSettings: (PermItem) -> Unit,
)

@Composable
fun SettingsScreen(vm: SettingsViewModel, actions: SettingsActions) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val keys by vm.keyState.collectAsStateWithLifecycle()
    val voices by vm.voices.collectAsStateWithLifecycle()
    val msg by vm.message.collectAsStateWithLifecycle()
    val perms by vm.perms.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refreshPerms() }
    SettingsContent(
        s, keys.ai, keys.tts, voices, msg, actions,
        update = vm::update, saveAiKey = vm::saveAiKey, saveTtsKey = vm::saveTtsKey,
        loadVoices = vm::loadVoices, preview = vm::previewVoice,
        perms = perms, hasTtsKey2 = keys.tts2, saveTtsKey2 = vm::saveTtsKey2,
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
    perms: List<PermItem> = emptyList(), hasTtsKey2: Boolean = false, saveTtsKey2: (String) -> Unit = {},
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

        SectionTitle("FRIDAY Voice")
        Text("Priority: the first tier that is configured speaks; if it fails the next one takes over. Android TTS is always the last resort.", color = FridayColors.TextDim, fontSize = 12.sp)
        val order = s.ttsPriority.filter { it != TtsProviderType.ANDROID }
        order.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}. ${t.label}", color = FridayColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = { update { it.copy(ttsPriority = move(order, i, -1) + TtsProviderType.ANDROID) } }, enabled = i > 0) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${t.label} up")
                }
                IconButton(onClick = { update { it.copy(ttsPriority = move(order, i, 1) + TtsProviderType.ANDROID) } }, enabled = i < order.lastIndex) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${t.label} down")
                }
            }
        }
        Text("${order.size + 1}. ${TtsProviderType.ANDROID.label} (always last)", color = FridayColors.TextDim, fontSize = 14.sp)

        Text("Tier — OpenAI-compatible", color = FridayColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        TextRow("TTS Endpoint", s.ttsEndpoint) { v -> update { it.copy(ttsEndpoint = v) } }
        TextRow("TTS Model", s.ttsModel) { v -> update { it.copy(ttsModel = v) } }
        TextRow("Voice", s.ttsVoice) { v -> update { it.copy(ttsVoice = v) } }
        KeyRow("TTS API Key", hasTtsKey, saveTtsKey)
        Text("Tier — ElevenLabs-compatible", color = FridayColors.TextDim, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        TextRow("Voice ID", s.ttsSecondaryVoice) { v -> update { it.copy(ttsSecondaryVoice = v) } }
        KeyRow("ElevenLabs API Key", hasTtsKey2, saveTtsKey2)

        Text("Android voice: ${s.androidVoice.ifBlank { "auto (English, female-leaning)" }}", color = FridayColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedButton(onClick = loadVoices) { Text("LIST ENGLISH VOICES") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            voices.take(24).forEach { v ->
                FilterChip(selected = s.androidVoice == v, label = { Text(v, fontSize = 11.sp) }, onClick = { update { it.copy(androidVoice = v) } })
            }
        }
        LabeledSlider("Speed ${"%.2f".format(s.ttsSpeed)}", s.ttsSpeed, 0.6f..1.6f) { v -> update { it.copy(ttsSpeed = v) } }
        LabeledSlider("Pitch ${"%.2f".format(s.ttsPitch)}", s.ttsPitch, 0.7f..1.5f) { v -> update { it.copy(ttsPitch = v) } }
        Text("While FRIDAY speaks:", color = FridayColors.TextDim, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioFocusMode.entries.forEach { m -> FilterChip(selected = s.audioFocusMode == m, label = { Text(m.label) }, onClick = { update { it.copy(audioFocusMode = m) } }) }
        }
        OutlinedButton(onClick = preview) { Text("PREVIEW VOICE") }

        SectionTitle("Subtitles")
        LabeledSlider("Subtitle Duration ${"%.1f".format(s.subtitleDurationMs / 1000f)} s", s.subtitleDurationMs / 1000f, 1f..8f) { v -> update { it.copy(subtitleDurationMs = (v * 1000).toInt()) } }
        LabeledSlider("Subtitle Size ${"%.1f".format(s.subtitleScale)}×", s.subtitleScale, 0.8f..1.8f) { v -> update { it.copy(subtitleScale = v) } }
        SwitchRow("Subtitle Overlay", "Show subtitles on top of other apps while FRIDAY is in the background (needs \"Display over other apps\")", s.overlaySubtitles) { v ->
            update { it.copy(overlaySubtitles = v) }
            val overlay = perms.firstOrNull { it.id == "overlay" }
            if (v && overlay != null && overlay.status != PermStatus.GRANTED) actions.openSettings(overlay)
        }
        SwitchRow("Always Show Subtitle", "Keep the last subtitle until the next conversation", s.alwaysShowSubtitle) { v -> update { it.copy(alwaysShowSubtitle = v) } }

        SectionTitle("Listening")
        Text("STT Engine: Android SpeechRecognizer (ko-KR)", color = FridayColors.TextDim, fontSize = 13.sp)
        Text("Wake Word: ${s.wakeWord}", color = FridayColors.TextDim, fontSize = 13.sp)
        SwitchRow("Background Assistant", "Foreground service with a visible notification", s.backgroundAssistant) { actions.setBackground(it) }
        SwitchRow("Barge-in", "Say FRIDAY while it talks to interrupt (Background Assistant)", s.bargeIn) { v -> update { it.copy(bargeIn = v) } }
        SwitchRow("Follow-up Mode", "Keep listening after an answer, no need to say FRIDAY again", s.followUpEnabled) { v -> update { it.copy(followUpEnabled = v) } }
        LabeledSlider("Follow-up Timeout ${s.followUpTimeoutSec} s", s.followUpTimeoutSec.toFloat(), 3f..15f) { v -> update { it.copy(followUpTimeoutSec = v.toInt()) } }
        SwitchRow("Auto Listen", "Listen again after FRIDAY asks a question", s.autoListen) { v -> update { it.copy(autoListen = v) } }
        SwitchRow("Voice Feedback", "Speak replies aloud", s.voiceFeedback) { v -> update { it.copy(voiceFeedback = v) } }

        SectionTitle("Weather")
        TextRow("Default City", s.defaultCity) { v -> update { it.copy(defaultCity = v) } }

        SectionTitle("Memory")
        SwitchRow("Conversation Memory", "Store chats on this device and use recent context", s.memoryEnabled) { v -> update { it.copy(memoryEnabled = v) } }

        SectionTitle("Proactive alerts (notifications only)")
        Text("Needs the Background Assistant. Event-driven; no constant polling.", color = FridayColors.TextDim, fontSize = 12.sp)
        SwitchRow("Low battery", "Warn when the battery is low and not charging", s.proactiveLowBattery) { v -> update { it.copy(proactiveLowBattery = v) } }
        SwitchRow("Upcoming event", "Remind about an event starting within 15 minutes", s.proactiveUpcomingEvent) { v -> update { it.copy(proactiveUpcomingEvent = v) } }
        SwitchRow("Weather warning", "Tell me when heavy rain is likely today", s.proactiveWeather) { v -> update { it.copy(proactiveWeather = v) } }

        SectionTitle("Appearance")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AccentTheme.entries.forEach { a ->
                FilterChip(selected = s.accent == a, label = { Text(a.label) }, onClick = { update { it.copy(accent = a) } })
            }
        }
        SwitchRow("AMOLED Mode", "Pure black background", s.amoled) { v -> update { it.copy(amoled = v) } }
        SwitchRow("Open in Ambient Mode", "Start the app in the always-on clock display", s.ambientMode) { v -> update { it.copy(ambientMode = v) } }
        SwitchRow("Developer Diagnostics", "Show the Diagnostics tab", s.developerDiagnostics) { v -> update { it.copy(developerDiagnostics = v) } }

        SectionTitle("Permission Center")
        perms.forEach { p -> PermissionRow(p, actions) }
        Text("Keys are stored encrypted with the Android Keystore and never leave this device except to your chosen provider. Notification and calendar contents are never sent to the AI.", color = FridayColors.TextDim, fontSize = 11.sp, modifier = Modifier.padding(vertical = 16.dp))
    }
}

@Composable
private fun PermissionRow(p: PermItem, actions: SettingsActions) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(p.title, fontSize = 14.sp, color = FridayColors.Text)
            Text(p.why, fontSize = 11.sp, color = FridayColors.TextDim)
            Text(
                p.status.name.replace('_', ' '), fontSize = 11.sp, letterSpacing = 1.sp,
                color = when (p.status) { PermStatus.GRANTED -> FridayColors.Ok; PermStatus.DENIED -> FridayColors.Error; else -> FridayColors.TextDim },
            )
        }
        when {
            p.status == PermStatus.GRANTED || p.kind == PermKind.DERIVED -> Unit
            p.kind == PermKind.SPECIAL -> OutlinedButton(onClick = { actions.openSettings(p) }) { Text("OPEN SETTINGS") }
            p.status == PermStatus.DENIED -> Column {
                OutlinedButton(onClick = { p.permission?.let(actions.requestPermission) }) { Text("ALLOW") }
                OutlinedButton(onClick = { actions.openSettings(p) }) { Text("SETTINGS") }
            }
            else -> OutlinedButton(onClick = { p.permission?.let(actions.requestPermission) }) { Text("ALLOW") }
        }
    }
}

private fun <T> move(list: List<T>, index: Int, delta: Int): List<T> {
    val m = list.toMutableList()
    val j = (index + delta).coerceIn(0, m.lastIndex)
    m.add(j, m.removeAt(index))
    return m
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
    // The whole row is the touch target, not just the small switch.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = FridayColors.Text, fontSize = 15.sp)
            Text(sub, color = FridayColors.TextDim, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Text(label, color = FridayColors.TextDim, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    Slider(value = value, onValueChange = onChange, valueRange = range)
}
