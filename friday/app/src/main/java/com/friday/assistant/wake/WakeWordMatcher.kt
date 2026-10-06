package com.friday.assistant.wake

/** Decides whether a transcript contains "FRIDAY" in the ways Korean/English recognizers spell it. */
object WakeWordMatcher {
    private val variants = listOf(
        "friday", "프라이데이", "프라이 데이", "후라이데이", "플라이데이", "프리데이", "프라이대이", "프라이데", "fry day", "fri day",
    )

    data class Match(val remainder: String)

    fun match(transcript: String): Match? {
        val lower = transcript.lowercase().trim()
        var best: Pair<Int, Int>? = null
        for (v in variants) {
            val i = lower.indexOf(v)
            if (i >= 0 && (best == null || i < best.first)) best = i to v.length
        }
        val (idx, len) = best ?: return null
        val rest = transcript.substring((idx + len).coerceAtMost(transcript.length))
            .trimStart(' ', ',', '.', '!', '?', '야', '아')
            .trim()
        return Match(rest)
    }
}
