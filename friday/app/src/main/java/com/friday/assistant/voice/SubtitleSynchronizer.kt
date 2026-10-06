package com.friday.assistant.voice

/** Splits a reply into sentence chunks so the Korean subtitle can follow the English voice sentence by sentence. */
object SubtitleSynchronizer {
    private val boundary = Regex("(?<=[.!?。])\\s+")

    fun split(speech: String, subtitle: String): List<SpeechChunk> {
        val s = speech.trim()
        val k = subtitle.trim()
        if (s.isEmpty()) return emptyList()
        val en = s.split(boundary).map { it.trim() }.filter { it.isNotEmpty() }
        val ko = k.split(boundary).map { it.trim() }.filter { it.isNotEmpty() }
        return if (en.size > 1 && en.size == ko.size) en.zip(ko) { a, b -> SpeechChunk(a, b) }
        else listOf(SpeechChunk(s, k))
    }
}
