package com.jarvis.assistant.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.data.model.AiProviderType
import com.jarvis.assistant.data.database.RoutineEntity
import com.jarvis.assistant.data.model.AccentStyle
import com.jarvis.assistant.data.model.AppSettings
import com.jarvis.assistant.data.model.VoiceStyle
import com.jarvis.assistant.data.model.Gesture
import com.jarvis.assistant.data.model.GestureAction
import com.jarvis.assistant.data.model.WakeSensitivity
import com.jarvis.assistant.data.model.SettingKeys
import com.jarvis.assistant.data.model.ThemeMode
import com.jarvis.assistant.data.model.TtsProviderType
import com.jarvis.assistant.data.repository.SettingsRepository
import com.jarvis.assistant.tts.JarvisSpeaker
import com.jarvis.assistant.tts.TtsException
import com.jarvis.assistant.tts.VoiceOption
import com.jarvis.assistant.ui.components.HudBackground
import com.jarvis.assistant.ui.theme.hudColors
import kotlinx.coroutines.launch

data class PermissionStatus(
    val mic: Boolean,
    val notifications: Boolean,
    val location: Boolean,
    val overlay: Boolean,
    val camera: Boolean = false,
    val contacts: Boolean = false,
    val calendar: Boolean = false,
    val activity: Boolean = false,
    val writeSettings: Boolean = false,
)

class PermissionActions(
    val requestMic: () -> Unit,
    val requestNotifications: () -> Unit,
    val requestLocation: () -> Unit,
    val requestOverlay: () -> Unit,
    val openAppSettings: () -> Unit,
    val requestCamera: () -> Unit = {},
    val requestContacts: () -> Unit = {},
    val requestCalendar: () -> Unit = {},
    val requestActivity: () -> Unit = {},
    val requestWriteSettings: () -> Unit = {},
)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    repo: SettingsRepository,
    speaker: JarvisSpeaker,
    permissions: PermissionStatus,
    actions: PermissionActions,
    proximityAvailable: Boolean = true,
    routines: List<RoutineEntity> = emptyList(),
    onSaveRoutine: (String, String) -> Unit = { _, _ -> },
    onDeleteRoutine: (Long) -> Unit = {},
    onBack: () -> Unit,
) {
    val colors = hudColors()
    val scope = rememberCoroutineScope()
    var keyVersion by remember { mutableIntStateOf(0) } // forces re-read of "has key" after save/remove
    var voices by remember { mutableStateOf<List<VoiceOption>>(emptyList()) }
    var testMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { voices = speaker.listAndroidVoices() }

    fun save(key: String, value: String) = scope.launch { repo.put(key, value) }
    fun save(key: String, value: Boolean) = scope.launch { repo.put(key, value) }

    Box(Modifier.fillMaxSize()) {
        HudBackground(accent = colors.accent)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = colors.text)
                }
                Text("SETTINGS", color = colors.text, fontSize = 16.sp, letterSpacing = 5.sp, fontFamily = FontFamily.Monospace)
            }
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            ) {
                // ---------------------------------------------------------------- AI
                SectionHeader("AI CORE")
                SettingsCard {
                    DropdownRow(
                        "AI Provider",
                        settings.aiProvider.label,
                        AiProviderType.values().map { it.name to it.label },
                    ) { save(SettingKeys.AI_PROVIDER, it) }
                    TextFieldRow(
                        "Endpoint", settings.aiEndpoint, settings.aiProvider, settings.aiProvider.defaultEndpoint,
                        KeyboardType.Uri,
                    ) { save(SettingKeys.aiEndpoint(settings.aiProvider), it) }
                    TextFieldRow("Model", settings.aiModel, settings.aiProvider, settings.aiProvider.defaultModel) {
                        save(SettingKeys.aiModel(settings.aiProvider), it)
                    }
                    SecretRow(
                        label = "API Key",
                        hasKey = remember(keyVersion, settings.aiProvider) { repo.hasAiApiKey(settings.aiProvider) },
                        resetKey = settings.aiProvider,
                        onSave = { repo.setAiApiKey(settings.aiProvider, it); keyVersion++ },
                        onRemove = { repo.setAiApiKey(settings.aiProvider, null); keyVersion++ },
                    )
                    Text(settings.aiProvider.freeNote, color = colors.textDim, fontSize = 11.sp)
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                ctx.startActivity(
                                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(settings.aiProvider.keyUrl))
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                        ) { Text("GET A KEY", fontSize = 12.sp, letterSpacing = 1.sp) }
                    }
                    SwitchRow(
                        "Auto-switch when a limit is hit",
                        "Add free keys for several providers (Gemini, Groq, Cerebras, OpenRouter). When one runs out, JARVIS continues with the next.",
                        settings.aiFailover,
                    ) { save(SettingKeys.AI_FAILOVER, it) }
                }

                // ---------------------------------------------------------------- TTS
                SectionHeader("VOICE OUTPUT")
                SettingsCard {
                    DropdownRow(
                        "TTS Provider",
                        settings.ttsProvider.label,
                        TtsProviderType.values().map { it.name to it.label },
                    ) { save(SettingKeys.TTS_PROVIDER, it) }
                    DropdownRow(
                        "Voice character",
                        settings.voiceStyle.label,
                        VoiceStyle.values().map { it.name to it.label },
                    ) { save(SettingKeys.VOICE_STYLE, it) }
                    Text(
                        "JARVIS (AI) adds depth, presence and a faint digital resonance to any voice. Use a British male system voice for the closest result.",
                        color = colors.textDim, fontSize = 11.sp,
                    )
                    if (settings.ttsProvider == TtsProviderType.ANDROID) {
                        val options = listOf("" to "Auto (JARVIS profile)") + voices.map { it.id to it.label.take(34) }
                        DropdownRow(
                            "Voice",
                            options.firstOrNull { it.first == settings.ttsVoice }?.second ?: "Auto (JARVIS profile)",
                            options,
                        ) { save(SettingKeys.ttsVoice(TtsProviderType.ANDROID), it.ifEmpty { " " }) }
                    } else {
                        TextFieldRow("Endpoint", settings.ttsEndpoint, settings.ttsProvider, settings.ttsProvider.defaultEndpoint) {
                            save(SettingKeys.ttsEndpoint(settings.ttsProvider), it)
                        }
                        TextFieldRow("Model", settings.ttsModel, settings.ttsProvider, settings.ttsProvider.defaultModel) {
                            save(SettingKeys.ttsModel(settings.ttsProvider), it)
                        }
                        TextFieldRow("Voice", settings.ttsVoice, settings.ttsProvider, settings.ttsProvider.defaultVoice) {
                            save(SettingKeys.ttsVoice(settings.ttsProvider), it)
                        }
                        SecretRow(
                            label = "TTS API Key",
                            hasKey = remember(keyVersion, settings.ttsProvider) { repo.hasTtsApiKey(settings.ttsProvider) },
                            resetKey = settings.ttsProvider,
                            onSave = { repo.setTtsApiKey(settings.ttsProvider, it); keyVersion++ },
                            onRemove = { repo.setTtsApiKey(settings.ttsProvider, null); keyVersion++ },
                        )
                    }
                    SliderRow("Speech Speed", settings.speechRate, 0.5f..1.5f, { "%.2fx".format(it) }) {
                        save(SettingKeys.SPEECH_RATE, it.toString())
                    }
                    SliderRow("Pitch", settings.pitch, 0.5f..1.5f, { "%.2f".format(it) }) {
                        save(SettingKeys.PITCH, it.toString())
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                scope.launch {
                                    testMessage = null
                                    try {
                                        speaker.speak("Good evening, sir. All systems are online and ready.")
                                    } catch (e: TtsException) {
                                        testMessage = "음성을 재생하지 못했습니다. 기기의 TTS 엔진을 확인해 주세요."
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                        ) { Text("TEST VOICE", fontSize = 12.sp, letterSpacing = 1.sp) }
                        testMessage?.let { Text(it, color = colors.textDim, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp)) }
                    }
                }

                // ---------------------------------------------------------------- Language
                SectionHeader("LANGUAGE")
                SettingsCard {
                    DropdownRow(
                        "Voice Input",
                        if (settings.inputLanguage == "en-US") "English (en-US)" else "Korean (ko-KR)",
                        listOf("ko-KR" to "Korean (ko-KR)", "en-US" to "English (en-US)"),
                    ) { save(SettingKeys.INPUT_LANGUAGE, it) }
                    Row(Modifier.fillMaxWidth()) {
                        Text("Voice Output", color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text("English", color = colors.accentSoft, fontSize = 13.sp)
                    }
                    SwitchRow("Korean Subtitles", "Show the Korean translation while JARVIS speaks", settings.subtitlesEnabled) {
                        save(SettingKeys.SUBTITLES, it)
                    }
                }

                // ---------------------------------------------------------------- Wake word / background
                SectionHeader("WAKE WORD & BACKGROUND")
                SettingsCard {
                    SwitchRow("Wake Word", "Respond when you say \"${AppSettings.WAKE_WORD}\" (자비스)", settings.wakeWordEnabled) {
                        save(SettingKeys.WAKE_WORD_ENABLED, it)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text("Wake Word", color = colors.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(AppSettings.WAKE_WORD, color = colors.accentSoft, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    }
                    SwitchRow(
                        "Background Assistant",
                        "Start the listening service automatically when the app opens. A notification always shows while the microphone is active.",
                        settings.backgroundAssistant,
                    ) { save(SettingKeys.BACKGROUND_ASSISTANT, it) }
                    DropdownRow(
                        "Wake sensitivity",
                        settings.wakeSensitivity.label,
                        WakeSensitivity.values().map { it.name to it.label },
                    ) { save(SettingKeys.WAKE_SENSITIVITY, it) }
                    Text(
                        "Strict = exact \"JARVIS\" only. Sensitive also accepts close mis-hearings (자비스, 자버스, Jervis…), at the cost of a few false wakes.",
                        color = colors.textDim, fontSize = 11.sp,
                    )
                    SwitchRow("Haptic feedback", "Short vibration when JARVIS wakes or a gesture is recognised", settings.wakeHaptic) {
                        save(SettingKeys.WAKE_HAPTIC, it)
                    }
                    SwitchRow(
                        "Instant shortcuts",
                        "Answer clear commands (time, battery, volume, routines…) locally without waiting for the AI",
                        settings.localShortcuts,
                    ) { save(SettingKeys.LOCAL_SHORTCUTS, it) }
                    SwitchRow("Voice Feedback", "Speak answers aloud (off = subtitles only)", settings.voiceFeedback) {
                        save(SettingKeys.VOICE_FEEDBACK, it)
                    }
                    SwitchRow("Auto Listen", "Keep listening for a follow-up after each answer", settings.autoListen) {
                        save(SettingKeys.AUTO_LISTEN, it)
                    }
                }

                // ---------------------------------------------------------------- Persona
                SectionHeader("PERSONA")
                SettingsCard {
                    TextFieldRow("How JARVIS addresses you (English)", settings.userTitle, "title", "Sir") {
                        save(SettingKeys.USER_TITLE, it)
                    }
                }

                // ---------------------------------------------------------------- Gestures
                SectionHeader("AIR GESTURES")
                SettingsCard {
                    SwitchRow(
                        "Proximity wave",
                        if (proximityAvailable) "Wave your hand over the top of the phone (works with the screen off while JARVIS is active)"
                        else "This phone has no proximity sensor",
                        settings.proximityGestures && proximityAvailable,
                    ) { save(SettingKeys.PROXIMITY_GESTURES, it) }
                    SwitchRow(
                        "Camera swipes",
                        "Swipe your hand in front of the front camera while the JARVIS screen is open. A \"GESTURE CAM\" label is shown whenever the camera is on; no frames are saved.",
                        settings.cameraGestures,
                    ) {
                        save(SettingKeys.CAMERA_GESTURES, it)
                        if (it && !permissions.camera) actions.requestCamera()
                    }
                    Gesture.values().forEach { g ->
                        val current = settings.gestureActions[g] ?: g.default
                        DropdownRow(
                            g.label,
                            current.label,
                            GestureAction.values().map { it.name to it.label },
                        ) { save(g.key, it) }
                    }
                }

                // ---------------------------------------------------------------- Weather
                SectionHeader("WEATHER")
                SettingsCard {
                    TextFieldRow(
                        "Default city (used without location permission)", settings.weatherCity, "city", "Seoul",
                    ) { save(SettingKeys.WEATHER_CITY, it) }
                }

                // ---------------------------------------------------------------- Appearance
                SectionHeader("APPEARANCE")
                SettingsCard {
                    DropdownRow(
                        "Theme",
                        settings.theme.label,
                        ThemeMode.values().map { it.name to it.label },
                    ) { save(SettingKeys.THEME, it) }
                    DropdownRow(
                        "HUD colour",
                        settings.accent.label,
                        AccentStyle.values().map { it.name to it.label },
                    ) { save(SettingKeys.ACCENT, it) }
                    SwitchRow("Interface sounds", "Boot chime, wake tone and error tone", settings.uiSounds) {
                        save(SettingKeys.UI_SOUNDS, it)
                    }
                    SwitchRow("Speak reminders", "Say reminders aloud when they fire", settings.speakReminders) {
                        save(SettingKeys.SPEAK_REMINDERS, it)
                    }
                    SwitchRow("Desk Mode", "Keep the screen on while JARVIS is open", settings.keepScreenOn) {
                        save(SettingKeys.KEEP_SCREEN_ON, it)
                    }
                }

                // ---------------------------------------------------------------- Routines
                SectionHeader("ROUTINES")
                SettingsCard {
                    Text(
                        "Built in: 굿모닝 · 굿나잇 · 외출 · 업무. Say \"굿모닝 루틴 시작\". Create your own below: one command per line, written as you would say it.",
                        color = colors.textDim, fontSize = 11.sp,
                    )
                    routines.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, color = colors.text, fontSize = 14.sp)
                                Text(r.steps.lines().joinToString(" → "), color = colors.textDim, fontSize = 11.sp, maxLines = 2)
                            }
                            TextButton(onClick = { onDeleteRoutine(r.id) }) { Text("DELETE", fontSize = 11.sp, color = colors.textDim) }
                        }
                    }
                    var routineName by remember { mutableStateOf("") }
                    var routineSteps by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = routineName, onValueChange = { routineName = it }, label = { Text("Routine name") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = routineSteps, onValueChange = { routineSteps = it },
                        label = { Text("Steps (one per line)") }, minLines = 3, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            onSaveRoutine(routineName, routineSteps)
                            routineName = ""
                            routineSteps = ""
                        },
                        enabled = routineName.isNotBlank() && routineSteps.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.Black),
                    ) { Text("SAVE ROUTINE", fontSize = 12.sp, letterSpacing = 1.sp) }
                }

                // ---------------------------------------------------------------- Permissions
                SectionHeader("PERMISSIONS")
                SettingsCard {
                    PermissionRow("Microphone", "Required for wake word and voice commands", permissions.mic, onGrant = actions.requestMic)
                    PermissionRow(
                        "Notifications", "Required to show the \"microphone active\" indicator", permissions.notifications,
                        onGrant = actions.requestNotifications,
                    )
                    PermissionRow("Location", "Optional: weather for where you are", permissions.location, onGrant = actions.requestLocation)
                    PermissionRow(
                        "Display over other apps",
                        "Optional: lets JARVIS open apps while it runs in the background",
                        permissions.overlay,
                        "OPEN",
                        actions.requestOverlay,
                    )
                    PermissionRow(
                        "Camera", "Optional: only for camera air gestures", permissions.camera,
                        onGrant = actions.requestCamera,
                    )
                    PermissionRow(
                        "Contacts", "Optional: say \"call Mom\" or \"text Mom\"", permissions.contacts,
                        onGrant = actions.requestContacts,
                    )
                    PermissionRow(
                        "Calendar", "Optional: \"오늘 일정 알려줘\"", permissions.calendar, onGrant = actions.requestCalendar,
                    )
                    PermissionRow(
                        "Physical activity", "Optional: step count", permissions.activity, onGrant = actions.requestActivity,
                    )
                    PermissionRow(
                        "Modify system settings", "Optional: screen brightness commands", permissions.writeSettings,
                        "OPEN", actions.requestWriteSettings,
                    )
                    PermissionRow(
                        "Blocked a permission?",
                        "Open the system page for JARVIS to change it manually",
                        granted = false,
                        actionLabel = "APP SETTINGS",
                        onGrant = actions.openAppSettings,
                    )
                }

                SectionHeader("ABOUT")
                SettingsCard {
                    Text("JARVIS V1.2 · voice is never recorded or stored; API keys are encrypted in the Android Keystore.", color = colors.textDim, fontSize = 12.sp)
                }
            }
        }
    }
}
