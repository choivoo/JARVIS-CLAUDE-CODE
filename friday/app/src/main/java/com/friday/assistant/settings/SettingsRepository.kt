package com.friday.assistant.settings

import android.content.Context
import android.content.SharedPreferences
import com.friday.assistant.security.SecureStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Non-secret settings in SharedPreferences, API keys in [SecureStore]. */
class SettingsRepository(private val prefs: SharedPreferences, private val secrets: SecureStore) {
    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<FridaySettings> = _settings.asStateFlow()
    val current: FridaySettings get() = _settings.value

    fun update(transform: (FridaySettings) -> FridaySettings) {
        val next = transform(_settings.value)
        _settings.value = next
        save(next)
    }

    fun aiApiKey(): String = secrets.get(KEY_AI).orEmpty()
    fun ttsApiKey(): String = secrets.get(KEY_TTS).orEmpty()
    fun ttsApiKey2(): String = secrets.get(KEY_TTS2).orEmpty()
    fun setTtsApiKey2(value: String) = store(KEY_TTS2, value)
    fun setAiApiKey(value: String) = store(KEY_AI, value)
    fun setTtsApiKey(value: String) = store(KEY_TTS, value)

    private fun store(key: String, value: String) {
        if (value.isBlank()) secrets.remove(key) else secrets.put(key, value.trim())
    }

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { n -> enumValues<E>().firstOrNull { it.name == n } } ?: default

    private fun load(): FridaySettings {
        val d = FridaySettings()
        val provider = enumOf("aiProvider", d.aiProvider)
        return FridaySettings(
            aiProvider = provider,
            aiModel = prefs.getString("aiModel", provider.defaultModel) ?: provider.defaultModel,
            aiEndpoint = prefs.getString("aiEndpoint", provider.defaultEndpoint) ?: provider.defaultEndpoint,
            ttsEndpoint = prefs.getString("ttsEndpoint", d.ttsEndpoint) ?: d.ttsEndpoint,
            ttsModel = prefs.getString("ttsModel", d.ttsModel) ?: d.ttsModel,
            ttsVoice = prefs.getString("ttsVoice", d.ttsVoice) ?: d.ttsVoice,
            androidVoice = prefs.getString("androidVoice", d.androidVoice) ?: d.androidVoice,
            ttsSpeed = prefs.getFloat("ttsSpeed", d.ttsSpeed),
            ttsPitch = prefs.getFloat("ttsPitch", d.ttsPitch),
            ttsPriority = prefs.getString("ttsPriority", null)?.split(",")?.mapNotNull { n -> TtsProviderType.entries.firstOrNull { it.name == n } }
                ?.takeIf { it.isNotEmpty() } ?: d.ttsPriority,
            ttsSecondaryVoice = prefs.getString("ttsSecondaryVoice", d.ttsSecondaryVoice) ?: d.ttsSecondaryVoice,
            audioFocusMode = enumOf("audioFocusMode", d.audioFocusMode),
            bargeIn = prefs.getBoolean("bargeIn", d.bargeIn),
            subtitleDurationMs = prefs.getInt("subtitleDurationMs", d.subtitleDurationMs),
            subtitleScale = prefs.getFloat("subtitleScale", d.subtitleScale),
            alwaysShowSubtitle = prefs.getBoolean("alwaysShowSubtitle", d.alwaysShowSubtitle),
            followUpEnabled = prefs.getBoolean("followUpEnabled", d.followUpEnabled),
            followUpTimeoutSec = prefs.getInt("followUpTimeoutSec", d.followUpTimeoutSec),
            proactiveLowBattery = prefs.getBoolean("proactiveLowBattery", d.proactiveLowBattery),
            proactiveUpcomingEvent = prefs.getBoolean("proactiveUpcomingEvent", d.proactiveUpcomingEvent),
            proactiveWeather = prefs.getBoolean("proactiveWeather", d.proactiveWeather),
            ambientMode = prefs.getBoolean("ambientMode", d.ambientMode),
            overlaySubtitles = prefs.getBoolean("overlaySubtitles", d.overlaySubtitles),
            sttEngine = prefs.getString("sttEngine", d.sttEngine) ?: d.sttEngine,
            wakeWord = d.wakeWord,
            backgroundAssistant = prefs.getBoolean("backgroundAssistant", d.backgroundAssistant),
            autoListen = prefs.getBoolean("autoListen", d.autoListen),
            voiceFeedback = prefs.getBoolean("voiceFeedback", d.voiceFeedback),
            defaultCity = prefs.getString("defaultCity", d.defaultCity) ?: d.defaultCity,
            memoryEnabled = prefs.getBoolean("memoryEnabled", d.memoryEnabled),
            accent = enumOf("accent", d.accent),
            amoled = prefs.getBoolean("amoled", d.amoled),
            developerDiagnostics = prefs.getBoolean("developerDiagnostics", d.developerDiagnostics),
        )
    }

    private fun save(s: FridaySettings) {
        prefs.edit()
            .putString("aiProvider", s.aiProvider.name).putString("aiModel", s.aiModel)
            .putString("aiEndpoint", s.aiEndpoint)
            .putString("ttsEndpoint", s.ttsEndpoint)
            .putString("ttsModel", s.ttsModel).putString("ttsVoice", s.ttsVoice)
            .putString("androidVoice", s.androidVoice)
            .putFloat("ttsSpeed", s.ttsSpeed).putFloat("ttsPitch", s.ttsPitch)
            .putString("ttsPriority", s.ttsPriority.joinToString(",") { it.name })
            .putString("ttsSecondaryVoice", s.ttsSecondaryVoice).putString("audioFocusMode", s.audioFocusMode.name)
            .putBoolean("bargeIn", s.bargeIn).putInt("subtitleDurationMs", s.subtitleDurationMs)
            .putFloat("subtitleScale", s.subtitleScale).putBoolean("alwaysShowSubtitle", s.alwaysShowSubtitle)
            .putBoolean("followUpEnabled", s.followUpEnabled).putInt("followUpTimeoutSec", s.followUpTimeoutSec)
            .putBoolean("proactiveLowBattery", s.proactiveLowBattery).putBoolean("proactiveUpcomingEvent", s.proactiveUpcomingEvent)
            .putBoolean("proactiveWeather", s.proactiveWeather).putBoolean("ambientMode", s.ambientMode).putBoolean("overlaySubtitles", s.overlaySubtitles)
            .putString("sttEngine", s.sttEngine)
            .putBoolean("backgroundAssistant", s.backgroundAssistant)
            .putBoolean("autoListen", s.autoListen).putBoolean("voiceFeedback", s.voiceFeedback)
            .putString("defaultCity", s.defaultCity).putBoolean("memoryEnabled", s.memoryEnabled)
            .putString("accent", s.accent.name).putBoolean("amoled", s.amoled)
            .putBoolean("developerDiagnostics", s.developerDiagnostics)
            .apply()
    }

    companion object {
        const val KEY_AI = "ai_api_key"
        const val KEY_TTS = "tts_api_key"
        const val KEY_TTS2 = "tts_api_key_2"
        fun create(context: Context, secrets: SecureStore) = SettingsRepository(
            context.applicationContext.getSharedPreferences("friday_settings", Context.MODE_PRIVATE), secrets,
        )
    }
}
