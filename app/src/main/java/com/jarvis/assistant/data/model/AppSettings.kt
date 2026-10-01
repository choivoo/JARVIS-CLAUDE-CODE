package com.jarvis.assistant.data.model

enum class AiProviderType(
    val label: String,
    val defaultEndpoint: String,
    val defaultModel: String,
) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", "gpt-4o-mini"),
    GEMINI("Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.0-flash"),
    OLLAMA("Ollama / Local LLM", "http://10.0.2.2:11434", "llama3.1"),
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
}

object SettingKeys {
    const val AI_PROVIDER = "ai.provider"
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
    const val WAKE_SENSITIVITY = "wake.sensitivity"
    const val WAKE_HAPTIC = "wake.haptic"
    const val USER_TITLE = "user.title"
    const val PROXIMITY_GESTURES = "gesture.proximity.enabled"
    const val CAMERA_GESTURES = "gesture.camera.enabled"

    fun aiEndpoint(p: AiProviderType) = "ai.endpoint.${p.name}"
    fun aiModel(p: AiProviderType) = "ai.model.${p.name}"
    fun ttsEndpoint(p: TtsProviderType) = "tts.endpoint.${p.name}"
    fun ttsModel(p: TtsProviderType) = "tts.model.${p.name}"
    fun ttsVoice(p: TtsProviderType) = "tts.voice.${p.name}"
}

/** Snapshot of every user setting, with the provider specific fields already resolved. */
data class AppSettings(
    val aiProvider: AiProviderType = AiProviderType.OPENAI_COMPATIBLE,
    val aiEndpoint: String = AiProviderType.OPENAI_COMPATIBLE.defaultEndpoint,
    val aiModel: String = AiProviderType.OPENAI_COMPATIBLE.defaultModel,
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
    val wakeHaptic: Boolean = true,
    /** How JARVIS addresses the user in English speech ("Sir", "Ma'am", a name). */
    val userTitle: String = "Sir",
    val proximityGestures: Boolean = true,
    val cameraGestures: Boolean = false,
    val gestureActions: Map<Gesture, GestureAction> = Gesture.values().associateWith { it.default },
) {
    companion object {
        const val WAKE_WORD = "JARVIS"

        fun from(map: Map<String, String>): AppSettings {
            fun enum(key: String) = map[key]
            val ai = AiProviderType.values().firstOrNull { it.name == enum(SettingKeys.AI_PROVIDER) }
                ?: AiProviderType.OPENAI_COMPATIBLE
            val tts = TtsProviderType.values().firstOrNull { it.name == enum(SettingKeys.TTS_PROVIDER) }
                ?: TtsProviderType.ANDROID
            fun bool(key: String, default: Boolean) = map[key]?.toBooleanStrictOrNull() ?: default
            return AppSettings(
                aiProvider = ai,
                aiEndpoint = map[SettingKeys.aiEndpoint(ai)]?.takeIf { it.isNotBlank() } ?: ai.defaultEndpoint,
                aiModel = map[SettingKeys.aiModel(ai)]?.takeIf { it.isNotBlank() } ?: ai.defaultModel,
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
                userTitle = map[SettingKeys.USER_TITLE]?.trim()?.takeIf { it.isNotEmpty() } ?: "Sir",
                proximityGestures = bool(SettingKeys.PROXIMITY_GESTURES, true),
                cameraGestures = bool(SettingKeys.CAMERA_GESTURES, false),
                gestureActions = Gesture.values().associateWith { g ->
                    GestureAction.values().firstOrNull { it.name == map[g.key] } ?: g.default
                },
            )
        }
    }
}
