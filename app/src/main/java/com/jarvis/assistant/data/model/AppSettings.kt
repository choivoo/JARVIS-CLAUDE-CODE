package com.jarvis.assistant.data.model

enum class AiProviderType(
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    /** Where to create a key, and a short note about the free allowance (limits change; check the console). */
    val keyUrl: String,
    val freeNote: String,
) {
    GEMINI(
        "Google Gemini (free)", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash-lite",
        "https://aistudio.google.com/apikey",
        "Free with no credit card. Flash-Lite allows roughly 1,000 requests a day and 30 a minute. Best default.",
    ),
    GROQ(
        "Groq (free)", "https://api.groq.com/openai/v1", "llama-3.1-8b-instant",
        "https://console.groq.com/keys",
        "Free and very fast. Has daily request and token caps per model.",
    ),
    CEREBRAS(
        "Cerebras (free)", "https://api.cerebras.ai/v1", "llama3.1-8b",
        "https://cloud.cerebras.ai/",
        "Free tier with a generous daily token allowance. Check the console for current limits.",
    ),
    OPENROUTER(
        "OpenRouter (free models)", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct:free",
        "https://openrouter.ai/keys",
        "Models ending in :free cost nothing, but are limited to about 20 requests a minute and 50 a day without credit.",
    ),
    OLLAMA(
        "Ollama / Local LLM", "http://10.0.2.2:11434", "llama3.1",
        "https://ollama.com/download",
        "Unlimited and free on your own PC. Use the PC's LAN address in the endpoint.",
    ),
    OPENAI_COMPATIBLE(
        "OpenAI / custom server", "https://api.openai.com/v1", "gpt-4o-mini",
        "https://platform.openai.com/api-keys",
        "Any OpenAI-compatible server (OpenAI, LM Studio, vLLM, ...). Paid on OpenAI.",
    ),
}

enum class TtsProviderType(
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
    val defaultVoice: String,
    val needsKey: Boolean,
) {
    ANDROID("Android system TTS", "", "", "", false),
    OPENAI_COMPATIBLE("OpenAI-compatible TTS", "https://api.openai.com/v1", "tts-1", "onyx", true),
    ELEVENLABS("ElevenLabs", "https://api.elevenlabs.io/v1", "eleven_multilingual_v2", "onwK4e9ZLuTAKqWW03F9", true),
}

enum class ThemeMode(val label: String) { DARK("Dark"), AMOLED("AMOLED"), LIGHT("Light") }

/** Post-processing applied to every synthesized sentence. JARVIS = deep, polished, slightly synthetic. */
enum class VoiceStyle(val label: String) { NATURAL("Natural"), JARVIS("JARVIS (AI)"), ROBOTIC("Robotic") }

/** HUD colour family. */
enum class AccentStyle(val label: String) {
    ARC_BLUE("Arc blue"), STARK_GOLD("Stark gold"), MATRIX_GREEN("Matrix green"), CRIMSON("Crimson")
}

/** How strictly "JARVIS" must be addressed before the assistant wakes. */
enum class WakeMode(val label: String, val hint: String) {
    CALL("Call me", "Only when the sentence starts with the name (\"자비스, …\"). Ignores people merely talking about JARVIS."),
    DOUBLE("Say it twice", "\"자비스 자비스\" – almost no accidental wakes, e.g. near a TV."),
    ANYWHERE("Anywhere", "Wakes whenever the name is heard, even mid-sentence."),
}

enum class WakeSensitivity(val label: String) {
    STRICT("Strict"), NORMAL("Normal"), SENSITIVE("Sensitive")
}

/** What an air gesture can trigger. */
enum class GestureAction(val label: String, val commandType: String?) {
    NONE("Nothing", null),
    LISTEN("Start / stop listening", null),
    STOP("Stop JARVIS", null),
    MUSIC_TOGGLE("Play / pause music", null),
    MUSIC_NEXT("Next track", "MUSIC_NEXT"),
    MUSIC_PREVIOUS("Previous track", "MUSIC_PREVIOUS"),
    VOLUME_UP("Volume up", "VOLUME_UP"),
    VOLUME_DOWN("Volume down", "VOLUME_DOWN"),
    FLASHLIGHT_TOGGLE("Toggle flashlight", null),
    TELL_TIME("Tell the time", "GET_TIME"),
    TELL_BATTERY("Tell the battery level", "GET_BATTERY"),
    TELL_WEATHER("Tell the weather", "WEATHER"),
}

enum class Gesture(val key: String, val label: String, val default: GestureAction) {
    WAVE("gesture.wave", "Wave over the phone", GestureAction.LISTEN),
    DOUBLE_WAVE("gesture.doubleWave", "Double wave over the phone", GestureAction.STOP),
    SWIPE_LEFT("gesture.swipeLeft", "Swipe left (camera)", GestureAction.MUSIC_NEXT),
    SWIPE_RIGHT("gesture.swipeRight", "Swipe right (camera)", GestureAction.MUSIC_PREVIOUS),
    SWIPE_UP("gesture.swipeUp", "Swipe up (camera)", GestureAction.VOLUME_UP),
    SWIPE_DOWN("gesture.swipeDown", "Swipe down (camera)", GestureAction.VOLUME_DOWN),
    PALM_HOLD("gesture.palmHold", "Open palm held (camera)", GestureAction.STOP),
    FIST_HOLD("gesture.fistHold", "Fist held (camera)", GestureAction.NONE),
    VICTORY_HOLD("gesture.victoryHold", "Victory sign held (camera)", GestureAction.LISTEN),
    THUMBS_UP_HOLD("gesture.thumbsUpHold", "Thumbs up held (camera)", GestureAction.MUSIC_TOGGLE),
}

object SettingKeys {
    const val AI_PROVIDER = "ai.provider"
    const val AI_FAILOVER = "ai.failover"
    const val TTS_PROVIDER = "tts.provider"
    const val SPEECH_RATE = "tts.rate"
    const val PITCH = "tts.pitch"
    const val INPUT_LANGUAGE = "lang.input"
    const val SUBTITLES = "lang.subtitles"
    const val WAKE_WORD_ENABLED = "wake.enabled"
    const val BACKGROUND_ASSISTANT = "background.enabled"
    const val VOICE_FEEDBACK = "voice.feedback"
    const val AUTO_LISTEN = "listen.auto"
    const val THEME = "ui.theme"
    const val KEEP_SCREEN_ON = "ui.keepScreenOn"
    const val WEATHER_CITY = "weather.city"
    const val LAST_LAT = "location.lat"
    const val LAST_LON = "location.lon"
    const val LOCAL_SHORTCUTS = "ai.localShortcuts"
    const val VOICE_STYLE = "tts.style"
    const val UI_SOUNDS = "ui.sounds"
    const val ACCENT = "ui.accent"
    const val SPEAK_REMINDERS = "reminders.speak"
    const val WAKE_SENSITIVITY = "wake.sensitivity"
    const val WAKE_MODE = "wake.mode"
    const val KEEP_AWAKE = "wake.keepAwake"
    const val WAKE_SCREEN = "wake.screen"
    const val WAKE_HAPTIC = "wake.haptic"
    const val USER_TITLE = "user.title"
    const val PROXIMITY_GESTURES = "gesture.proximity.enabled"
    const val CAMERA_GESTURES = "gesture.camera.enabled"
    const val HOLO_COUNT = "holo.count"
    const val HOLO_PANELS = "holo.panels"
    const val HOLO_PARALLAX = "holo.parallax"
    const val PINCH_SENSITIVITY = "gesture.pinch.sensitivity"
    const val DWELL_CLICK = "gesture.dwell.enabled"

    fun aiEndpoint(p: AiProviderType) = "ai.endpoint.${p.name}"
    fun aiModel(p: AiProviderType) = "ai.model.${p.name}"
    fun ttsEndpoint(p: TtsProviderType) = "tts.endpoint.${p.name}"
    fun ttsModel(p: TtsProviderType) = "tts.model.${p.name}"
    fun ttsVoice(p: TtsProviderType) = "tts.voice.${p.name}"
}

/** Snapshot of every user setting, with the provider specific fields already resolved. */
data class AppSettings(
    val aiProvider: AiProviderType = AiProviderType.GEMINI,
    val aiEndpoint: String = AiProviderType.GEMINI.defaultEndpoint,
    val aiModel: String = AiProviderType.GEMINI.defaultModel,
    /** When the main AI hits its free limit, try the other providers that have a key. */
    val aiFailover: Boolean = true,
    val ttsProvider: TtsProviderType = TtsProviderType.ANDROID,
    val ttsEndpoint: String = "",
    val ttsModel: String = "",
    /** Provider specific voice: Android voice name, OpenAI voice id or ElevenLabs voice id. Empty = auto. */
    val ttsVoice: String = "",
    val speechRate: Float = 0.92f,
    val pitch: Float = 0.85f,
    val inputLanguage: String = "ko-KR",
    val subtitlesEnabled: Boolean = true,
    val wakeWordEnabled: Boolean = true,
    val backgroundAssistant: Boolean = false,
    val voiceFeedback: Boolean = true,
    val autoListen: Boolean = false,
    val theme: ThemeMode = ThemeMode.AMOLED,
    val keepScreenOn: Boolean = false,
    val weatherCity: String = "Seoul",
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.NORMAL,
    val wakeMode: WakeMode = WakeMode.CALL,
    /** Hold a partial wake lock while standby runs so listening continues with the screen off. */
    val keepAwakeStandby: Boolean = true,
    /** Turn the screen on and show JARVIS over the lock screen when called. */
    val wakeScreen: Boolean = true,
    val voiceStyle: VoiceStyle = VoiceStyle.JARVIS,
    /** Run clear-cut device commands locally without waiting for the AI (faster, works offline, no API cost). */
    val localShortcuts: Boolean = true,
    val uiSounds: Boolean = true,
    val accent: AccentStyle = AccentStyle.ARC_BLUE,
    val speakReminders: Boolean = true,
    val wakeHaptic: Boolean = true,
    /** How JARVIS addresses the user in English speech ("Sir", "Ma'am", a name). */
    val userTitle: String = "Sir",
    val proximityGestures: Boolean = true,
    val cameraGestures: Boolean = false,
    /** 0 = firm pinch needed, 1 = light touch is enough. */
    val pinchSensitivity: Float = 0.5f,
    val dwellClick: Boolean = true,
    /** How many holographic windows open by default (4..11). */
    val holoCount: Int = 6,
    /** Panels chosen in Settings; empty = the first [holoCount] of the default order. */
    val holoPanels: List<com.jarvis.assistant.holo.HoloPanel> = emptyList(),
    val holoParallax: Boolean = true,
    val gestureActions: Map<Gesture, GestureAction> = Gesture.values().associateWith { it.default },
) {
    companion object {
        const val WAKE_WORD = "JARVIS"

        fun from(map: Map<String, String>): AppSettings {
            fun enum(key: String) = map[key]
            val ai = AiProviderType.values().firstOrNull { it.name == enum(SettingKeys.AI_PROVIDER) }
                ?: AiProviderType.GEMINI
            val tts = TtsProviderType.values().firstOrNull { it.name == enum(SettingKeys.TTS_PROVIDER) }
                ?: TtsProviderType.ANDROID
            fun bool(key: String, default: Boolean) = map[key]?.toBooleanStrictOrNull() ?: default
            return AppSettings(
                aiProvider = ai,
                aiEndpoint = map[SettingKeys.aiEndpoint(ai)]?.takeIf { it.isNotBlank() } ?: ai.defaultEndpoint,
                aiModel = map[SettingKeys.aiModel(ai)]?.takeIf { it.isNotBlank() } ?: ai.defaultModel,
                aiFailover = bool(SettingKeys.AI_FAILOVER, true),
                ttsProvider = tts,
                ttsEndpoint = map[SettingKeys.ttsEndpoint(tts)]?.takeIf { it.isNotBlank() } ?: tts.defaultEndpoint,
                ttsModel = map[SettingKeys.ttsModel(tts)]?.takeIf { it.isNotBlank() } ?: tts.defaultModel,
                ttsVoice = map[SettingKeys.ttsVoice(tts)]?.takeIf { it.isNotBlank() } ?: tts.defaultVoice,
                speechRate = map[SettingKeys.SPEECH_RATE]?.toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: 0.92f,
                pitch = map[SettingKeys.PITCH]?.toFloatOrNull()?.coerceIn(0.5f, 1.5f) ?: 0.85f,
                inputLanguage = map[SettingKeys.INPUT_LANGUAGE]?.takeIf { it.isNotBlank() } ?: "ko-KR",
                subtitlesEnabled = bool(SettingKeys.SUBTITLES, true),
                wakeWordEnabled = bool(SettingKeys.WAKE_WORD_ENABLED, true),
                backgroundAssistant = bool(SettingKeys.BACKGROUND_ASSISTANT, false),
                voiceFeedback = bool(SettingKeys.VOICE_FEEDBACK, true),
                autoListen = bool(SettingKeys.AUTO_LISTEN, false),
                theme = ThemeMode.values().firstOrNull { it.name == enum(SettingKeys.THEME) } ?: ThemeMode.AMOLED,
                keepScreenOn = bool(SettingKeys.KEEP_SCREEN_ON, false),
                weatherCity = map[SettingKeys.WEATHER_CITY]?.takeIf { it.isNotBlank() } ?: "Seoul",
                lastLat = map[SettingKeys.LAST_LAT]?.toDoubleOrNull(),
                lastLon = map[SettingKeys.LAST_LON]?.toDoubleOrNull(),
                wakeSensitivity = WakeSensitivity.values().firstOrNull { it.name == enum(SettingKeys.WAKE_SENSITIVITY) }
                    ?: WakeSensitivity.NORMAL,
                wakeHaptic = bool(SettingKeys.WAKE_HAPTIC, true),
                wakeMode = WakeMode.values().firstOrNull { it.name == enum(SettingKeys.WAKE_MODE) } ?: WakeMode.CALL,
                keepAwakeStandby = bool(SettingKeys.KEEP_AWAKE, true),
                wakeScreen = bool(SettingKeys.WAKE_SCREEN, true),
                voiceStyle = VoiceStyle.values().firstOrNull { it.name == enum(SettingKeys.VOICE_STYLE) } ?: VoiceStyle.JARVIS,
                uiSounds = bool(SettingKeys.UI_SOUNDS, true),
                localShortcuts = bool(SettingKeys.LOCAL_SHORTCUTS, true),
                accent = AccentStyle.values().firstOrNull { it.name == enum(SettingKeys.ACCENT) } ?: AccentStyle.ARC_BLUE,
                speakReminders = bool(SettingKeys.SPEAK_REMINDERS, true),
                userTitle = map[SettingKeys.USER_TITLE]?.trim()?.takeIf { it.isNotEmpty() } ?: "Sir",
                proximityGestures = bool(SettingKeys.PROXIMITY_GESTURES, true),
                cameraGestures = bool(SettingKeys.CAMERA_GESTURES, false),
                pinchSensitivity = map[SettingKeys.PINCH_SENSITIVITY]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f,
                dwellClick = bool(SettingKeys.DWELL_CLICK, true),
                holoCount = map[SettingKeys.HOLO_COUNT]?.toIntOrNull()?.coerceIn(4, 11) ?: 6,
                holoPanels = map[SettingKeys.HOLO_PANELS].orEmpty().split(',').mapNotNull { n ->
                    com.jarvis.assistant.holo.HoloPanel.values().firstOrNull { it.name == n.trim() }
                }.distinct().take(11),
                holoParallax = bool(SettingKeys.HOLO_PARALLAX, true),
                gestureActions = Gesture.values().associateWith { g ->
                    GestureAction.values().firstOrNull { it.name == map[g.key] } ?: g.default
                },
            )
        }
    }
}
