package com.jarvis.assistant.speech

import com.jarvis.assistant.data.model.WakeSensitivity

/**
 * Finds the wake word in recognizer output. Korean recognizers mangle "JARVIS" in many ways
 * ("자비스", "쟈비스", "자브스", "Jervis", ...), so matching is layered:
 *  1. known aliases anywhere in the text (all sensitivities),
 *  2. fuzzy match (edit distance) on the first words (NORMAL: Latin tokens, SENSITIVE: Latin + Korean).
 */
object WakeWordMatcher {
    private val pattern = Regex(
        "(?i)(?:hey\\s+|hi\\s+|okay\\s+|헤이\\s*)?" +
            "(jarvis|jarvice|jarves|jarvus|jervis|jarvas|garvis|자비스|쟈비스|자비쓰|자비즈|자르비스|자브스|자비수|자비서)",
    )
    private val leadingFiller = Regex("^[\\s,.!?~\\-:;]+")
    private val particle = Regex("^(야|아|님)(?=[\\s,.!?]|$)")
    private val word = Regex("[\\p{L}\\p{N}]+")

    /** Common Korean words one edit away from "자비스" that must never wake the assistant. */
    private val koreanBlocklist = setOf("서비스", "서비서", "사비스", "자비", "자비로", "자비롭게")
    private val latinBlocklist = setOf("service", "services", "jarvis's")

    fun match(text: String, sensitivity: WakeSensitivity = WakeWordSensitivityDefault): WakeDetection? {
        exactMatch(text)?.let { return it }
        if (sensitivity == WakeSensitivity.STRICT) return null
        return fuzzyMatch(text, sensitivity)
    }

    fun match(candidates: List<String>, sensitivity: WakeSensitivity = WakeWordSensitivityDefault): WakeDetection? {
        // Prefer the hypothesis that carries trailing text so a one-breath command is not lost.
        val hits = candidates.mapNotNull { match(it, sensitivity) }
        return hits.firstOrNull { it.trailingText != null } ?: hits.firstOrNull()
    }

    private fun exactMatch(text: String): WakeDetection? {
        val m = pattern.find(text) ?: return null
        return detection(m.value.trim(), text.substring(m.range.last + 1))
    }

    private fun fuzzyMatch(text: String, sensitivity: WakeSensitivity): WakeDetection? {
        // Only the first three words can be the wake word ("자비스 ..."), which keeps false positives low.
        val words = word.findAll(text).take(3).toList()
        for (w in words) {
            val token = w.value.lowercase()
            val isHangul = token.any { it in '가'..'힣' }
            val hit = if (isHangul) {
                sensitivity == WakeSensitivity.SENSITIVE && token !in koreanBlocklist && fuzzyKorean(token)
            } else {
                token !in latinBlocklist && fuzzyLatin(token, sensitivity)
            }
            if (hit) return detection(w.value, text.substring(w.range.last + 1))
        }
        return null
    }

    private fun fuzzyLatin(token: String, sensitivity: WakeSensitivity): Boolean {
        if (token.length !in 5..8) return false
        val limit = if (sensitivity == WakeSensitivity.SENSITIVE) 2 else 1
        return levenshtein(token, "jarvis") <= limit
    }

    private fun fuzzyKorean(token: String): Boolean {
        // Allow "자비스야" style endings, then a single syllable difference from 자비스.
        val core = if (token.length == 4 && token.endsWith("야")) token.dropLast(1) else token
        return core.length == 3 && levenshtein(core, "자비스") <= 1 && core[1] == '비'
    }

    private fun detection(phrase: String, after: String): WakeDetection {
        var rest = leadingFiller.replace(after, "")
        rest = particle.replace(rest, "")
        rest = leadingFiller.replace(rest, "").trim()
        return WakeDetection(phrase.trim(), rest.ifEmpty { null })
    }

    internal fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            prev = cur
        }
        return prev[b.length]
    }
}

private val WakeWordSensitivityDefault = WakeSensitivity.NORMAL
