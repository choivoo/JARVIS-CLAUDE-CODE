package com.friday.assistant.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.friday.assistant.AppContainer
import com.friday.assistant.core.CoreState
import com.friday.assistant.core.SubtitleState
import com.friday.assistant.data.MessageEntity
import com.friday.assistant.diag.DiagResult
import com.friday.assistant.diag.DiagnosticsRunner
import com.friday.assistant.settings.FridaySettings
import com.friday.assistant.voice.say
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val c: AppContainer) : ViewModel() {
    val core: StateFlow<CoreState> = combine(c.controller.core, c.network.online) { s, online ->
        if (s == CoreState.IDLE && !online) CoreState.OFFLINE else s
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CoreState.IDLE)
    val subtitle: StateFlow<SubtitleState> get() = c.controller.subtitle
    val partial: StateFlow<String> get() = c.controller.partial
    val micLevel get() = c.controller.micLevel
    val amplitude get() = c.controller.amplitude
    val settings: StateFlow<FridaySettings> get() = c.settingsRepo.settings
    val card get() = c.controller.card
    val koreanVoice get() = c.controller.koreanVoice
    val contextSize get() = c.controller.contextSize
    val serviceRunning get() = c.serviceRunning
    val online get() = c.network.online

    fun listen() = c.controller.startListening()
    fun stop() = c.controller.stop()
}

class ConversationViewModel(private val c: AppContainer) : ViewModel() {
    val messages: StateFlow<List<MessageEntity>> = c.conversations.messages
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun send(text: String) = c.controller.submitText(text)
    fun clear() { viewModelScope.launch { c.conversations.clear() } }
}

class SettingsViewModel(val c: AppContainer) : ViewModel() {
    val settings: StateFlow<FridaySettings> get() = c.settingsRepo.settings
    val keyState = MutableStateFlow(KeyState(hasAiKey(), hasTtsKey(), hasTtsKey2()))
    val voices = MutableStateFlow<List<String>>(emptyList())
    val message = MutableStateFlow<String?>(null)

    data class KeyState(val ai: Boolean, val tts: Boolean, val tts2: Boolean = false)
    val perms = MutableStateFlow(com.friday.assistant.permission.PermissionCenter.items(c.permissionSnapshot))
    fun refreshPerms() { perms.value = com.friday.assistant.permission.PermissionCenter.items(c.permissionSnapshot) }

    private fun hasAiKey() = runCatching { c.settingsRepo.aiApiKey().isNotBlank() }.getOrDefault(false)
    private fun hasTtsKey2() = runCatching { c.settingsRepo.ttsApiKey2().isNotBlank() }.getOrDefault(false)
    private fun hasTtsKey() = runCatching { c.settingsRepo.ttsApiKey().isNotBlank() }.getOrDefault(false)

    fun update(t: (FridaySettings) -> FridaySettings) = c.settingsRepo.update(t)

    fun saveAiKey(v: String) = save { c.settingsRepo.setAiApiKey(v) }
    fun saveTtsKey(v: String) = save { c.settingsRepo.setTtsApiKey(v) }
    fun saveTtsKey2(v: String) = save { c.settingsRepo.setTtsApiKey2(v) }

    private fun save(block: () -> Unit) {
        message.value = try { block(); null } catch (e: Exception) { "Could not store the key securely on this device." }
        keyState.value = KeyState(hasAiKey(), hasTtsKey(), hasTtsKey2())
    }

    fun loadVoices() { viewModelScope.launch { voices.value = c.androidTtsProvider.availableVoices() } }
    fun previewVoice() { viewModelScope.launch { runCatching { c.speaker.say("Hello, I'm FRIDAY. How can I help?") } } }
}

class DiagnosticsViewModel(private val c: AppContainer) : ViewModel() {
    private val runner = DiagnosticsRunner(c)
    val results = MutableStateFlow<Map<String, DiagResult>>(emptyMap())
    val running = MutableStateFlow<String?>(null)
    val tests get() = runner.tests
    val latency get() = c.controller.latency

    fun run(id: String) {
        viewModelScope.launch {
            running.value = id
            results.value = results.value + (id to runner.run(id))
            running.value = null
        }
    }

    fun runAll() {
        viewModelScope.launch {
            for (t in runner.tests) {
                running.value = t.id
                results.value = results.value + (t.id to runner.run(t.id))
            }
            running.value = null
        }
    }
}

inline fun <reified T : ViewModel> factory(crossinline create: () -> T) = viewModelFactory { initializer { create() } }
