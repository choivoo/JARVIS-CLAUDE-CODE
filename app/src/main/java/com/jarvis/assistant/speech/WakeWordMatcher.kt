package com.jarvis.assistant.speech

/** Finds the wake word in recognizer output, tolerating common Korean / English mis-hearings. */
object WakeWordMatcher {
    private val pattern = Regex(
        "(?i)(?:hey\\s+|hi\\s+|헤이\\s*)?(jarvis|jarvice|jarves|자비스|쟈비스|자비쓰|자비즈|자르비스|자브스)",
    )
    private val leadingFiller = Regex("^[\\s,.!?~\\-:;]+")
    private val particle = Regex("^(야|아|님)(?=[\\s,.!?]|$)")

    fun match(text: String): WakeDetection? {
        val m = pattern.find(text) ?: return null
        var rest = text.substring(m.range.last + 1)
        rest = leadingFiller.replace(rest, "")
        rest = particle.replace(rest, "")
        rest = leadingFiller.replace(rest, "").trim()
        return WakeDetection(m.value.trim(), rest.ifEmpty { null })
    }

    fun match(candidates: List<String>): WakeDetection? {
        // Prefer the hypothesis that carries trailing text so a one-breath command is not lost.
        val hits = candidates.mapNotNull { match(it) }
        return hits.firstOrNull { it.trailingText != null } ?: hits.firstOrNull()
    }
}
