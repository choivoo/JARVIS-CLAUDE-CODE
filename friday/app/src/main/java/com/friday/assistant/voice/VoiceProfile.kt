package com.friday.assistant.voice

enum class Emphasis { NORMAL, WARNING }

/** One spoken sentence (English) with the Korean subtitle that belongs to it. */
data class SpeechChunk(val speech: String, val subtitle: String)

/** Everything a Speaker needs for one reply. Callbacks fire on the speaking coroutine. */
class SpeechRequest(
    val chunks: List<SpeechChunk>,
    val emphasis: Emphasis = Emphasis.NORMAL,
    val onChunkStart: (index: Int) -> Unit = {},
    val onFirstAudio: () -> Unit = {},
    /** Chunks hold Korean text and must be spoken with a Korean voice. */
    val korean: Boolean = false,
)

/**
 * FRIDAY's original voice character: a calm, clear, intelligent English female assistant voice.
 * It describes delivery only; it does not imitate any real person or character.
 */
class VoiceProfile(private val baseInstructions: String, private val warningRate: Float, private val warningPitch: Float) {
    fun instructions(e: Emphasis): String = when (e) {
        Emphasis.NORMAL -> baseInstructions
        Emphasis.WARNING -> "$baseInstructions For this line be a little slower and more precise, clearly articulating the important words; stay composed, never theatrical."
    }

    fun rateFactor(e: Emphasis) = if (e == Emphasis.WARNING) warningRate else 1f
    fun pitchFactor(e: Emphasis) = if (e == Emphasis.WARNING) warningPitch else 1f

    companion object {
        val FRIDAY = VoiceProfile(
            baseInstructions = "Voice: a calm, clear, intelligent female AI assistant with a refined, slightly futuristic tone. " +
                "Natural, warm intonation and smooth sentence linking; concise and quick, never robotic and never over-acted.",
            warningRate = 0.92f,
            warningPitch = 0.96f,
        )
    }
}

/** Speak one plain line with no subtitle (used for diagnostics and previews). */
suspend fun Speaker.say(text: String, emphasis: Emphasis = Emphasis.NORMAL) =
    speak(SpeechRequest(listOf(SpeechChunk(text, "")), emphasis))
