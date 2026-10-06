package com.friday.assistant.settings

enum class AiProviderType(val label: String, val defaultEndpoint: String, val defaultModel: String, val needsKey: Boolean) {
    OPENAI_COMPATIBLE("OpenAI-compatible", "https://api.openai.com/v1", "gpt-4o-mini", true),
    GEMINI("Google Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini-2.5-flash", true),
    OLLAMA("Ollama (local)", "http://192.168.0.10:11434", "llama3.1", false),
}

enum class TtsProviderType(val label: String) {
    ANDROID("Android TTS"),
    OPENAI_COMPATIBLE("OpenAI-compatible TTS"),
    ELEVENLABS("ElevenLabs-compatible"),
}

enum class AccentTheme(val label: String) { VIOLET("Violet"), MAGENTA("Magenta"), BLUE("Blue") }

data class FridaySettings(
    val aiProvider: AiProviderType = AiProviderType.GEMINI,
    val aiModel: String = AiProviderType.GEMINI.defaultModel,
    val aiEndpoint: String = AiProviderType.GEMINI.defaultEndpoint,

    val ttsProvider: TtsProviderType = TtsProviderType.ANDROID,
    val ttsEndpoint: String = "https://api.openai.com/v1",
    val ttsModel: String = "gpt-4o-mini-tts",
    /** Cloud voice name (OpenAI: "nova", ElevenLabs: voice id). */
    val ttsVoice: String = "nova",
    /** Android TTS voice name, blank = auto-pick an English female-leaning voice. */
    val androidVoice: String = "",
    val ttsSpeed: Float = 1.0f,
    val ttsPitch: Float = 1.08f,

    val sttEngine: String = "android",
    val wakeWord: String = "FRIDAY",
    val backgroundAssistant: Boolean = false,
    val autoListen: Boolean = false,
    val voiceFeedback: Boolean = true,
    val defaultCity: String = "Seoul",
    val memoryEnabled: Boolean = true,
    val accent: AccentTheme = AccentTheme.VIOLET,
    val amoled: Boolean = true,
    val developerDiagnostics: Boolean = true,
)
