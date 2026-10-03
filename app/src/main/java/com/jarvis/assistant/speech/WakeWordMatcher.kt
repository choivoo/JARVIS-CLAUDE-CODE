package com.jarvis.assistant.speech

import com.jarvis.assistant.data.model.WakeMode
import com.jarvis.assistant.data.model.WakeSensitivity

/**
 * Finds a *call* to the assistant in recognizer output, not just the word.
 *
 * People say "JARVIS" all the time without addressing the assistant ("어제 자비스가 말했는데",
 * "the Jarvis movie"). So in [WakeMode.CALL] (default) the name only counts when
 *  - it is among the first words of the sentence,
 *  - it is said as a name ("자비스", "자비스야", "Jarvis,") and not with a particle that makes it
 *    the subject or topic of a sentence ("자비스가", "자비스는", "자비스를"), and
 *  - what follows does not read like a story about it ("자비스 영화 봤어?").
 * [WakeMode.DOUBLE] needs the name twice, [WakeMode.ANYWHERE] restores the old behaviour.
 * Mis-hearings are tolerated with an alias list and, depending on [WakeSensitivity], edit distance.
 */
object WakeWordMatcher {
    private val aliases = listOf(
        "자르비스", "jarvice", "jarvis", "jarves", "jarvus", "jervis", "jarvas", "garvis",
        "자비스", "쟈비스", "자비쓰", "자비즈", "자브스", "자비수", "자비서",
    ).sortedByDescending { it.length }

    private val fillers = setOf("hey", "hi", "okay", "ok", "yo", "헤이", "저기", "야", "아", "여보세요")

    /** Endings that still mean "I am calling you". */
    private val vocativeSuffixes = setOf("", "야", "아", "님", "씨", "여", "야~", "아~")

    private val leadingFiller = Regex("^[\\s,.!?~\\-:;]+")
    private val particle = Regex("^(야|아|님)(?=[\\s,.!?]|$)")
    private val word = Regex("[\\p{L}\\p{N}]+")

    private val koreanBlocklist = setOf("서비스", "서비서", "사비스", "자비", "자비로", "자비롭게")
    private val latinBlocklist = setOf("service", "services")

    /** What follows a mention rather than a call. */
    private val mentionTail = Regex("^(영화|얘기|이야기|캐릭터|라는|라고|란|이란|말이야|말인데|처럼|같은|에 대해|에 대한|가 말|가 그|ai 얘기)")
    private val latinMentionTail = setOf("was", "were", "said", "says", "had", "from", "movie", "character", "film")
    private const val MAX_TAIL_WORDS = 18

    fun match(
        text: String,
        sensitivity: WakeSensitivity = WakeSensitivity.NORMAL,
        mode: WakeMode = WakeMode.CALL,
    ): WakeDetection? {
        val words = word.findAll(text).toList()
        if (words.isEmpty()) return null

        val hit = when (mode) {
            WakeMode.ANYWHERE -> exact(text, words, anywhere = true) ?: fuzzy(text, words, sensitivity, anywhere = true)
            WakeMode.CALL -> exact(text, words, anywhere = false) ?: fuzzy(text, words, sensitivity, anywhere = false)
            WakeMode.DOUBLE -> doubleCall(text, words, sensitivity)
        } ?: return null

        if (mode != WakeMode.ANYWHERE && looksLikeMention(hit.trailingText)) return null
        return hit
    }

    fun match(
        candidates: List<String>,
        sensitivity: WakeSensitivity = WakeSensitivity.NORMAL,
        mode: WakeMode = WakeMode.CALL,
    ): WakeDetection? {
        // Prefer the hypothesis that carries trailing text so a one-breath command is not lost.
        val hits = candidates.mapNotNull { match(it, sensitivity, mode) }
        return hits.firstOrNull { it.trailingText != null } ?: hits.firstOrNull()
    }

    // ------------------------------------------------------------------ rules

    /** Index of the first word that is not a greeting filler ("hey", "야"). */
    private fun firstContentIndex(words: List<MatchResult>): Int {
        var i = 0
        while (i < words.size - 1 && words[i].value.lowercase() in fillers) i++
        return i
    }

    private fun exact(text: String, words: List<MatchResult>, anywhere: Boolean): WakeDetection? {
        val start = if (anywhere) 0 else firstContentIndex(words)
        val last = if (anywhere) words.lastIndex else minOf(words.lastIndex, start + 1)
        for (i in start..last) {
            val token = words[i].value.lowercase()
            val alias = aliases.firstOrNull { token.startsWith(it) } ?: continue
            val suffix = token.substring(alias.length)
            val latin = alias.all { it in 'a'..'z' }
            if (!anywhere) {
                if (latin && suffix.isNotEmpty()) continue
                if (!latin && suffix !in vocativeSuffixes) continue          // 자비스가 / 자비스는 / 자비스를 …
            }
            return detection(words[i].value, text.substring(words[i].range.last + 1))
        }
        return null
    }

    private fun doubleCall(text: String, words: List<MatchResult>, sensitivity: WakeSensitivity): WakeDetection? {
        val start = firstContentIndex(words)
        if (start + 1 > words.lastIndex) return null
        val first = isNameToken(words[start].value, sensitivity)
        val second = isNameToken(words[start + 1].value, sensitivity)
        if (!first || !second) return null
        return detection("${words[start].value} ${words[start + 1].value}", text.substring(words[start + 1].range.last + 1))
    }

    private fun isNameToken(raw: String, sensitivity: WakeSensitivity): Boolean {
        val token = raw.lowercase()
        val alias = aliases.firstOrNull { token.startsWith(it) }
        if (alias != null) {
            val suffix = token.substring(alias.length)
            return suffix in vocativeSuffixes
        }
        if (sensitivity == WakeSensitivity.STRICT) return false
        return if (token.any { it in '가'..'힣' }) sensitivity == WakeSensitivity.SENSITIVE && fuzzyKorean(token) && token !in koreanBlocklist
        else token !in latinBlocklist && fuzzyLatin(token, sensitivity)
    }

    private fun fuzzy(text: String, words: List<MatchResult>, sensitivity: WakeSensitivity, anywhere: Boolean): WakeDetection? {
        if (sensitivity == WakeSensitivity.STRICT) return null
        val start = if (anywhere) 0 else firstContentIndex(words)
        val last = if (anywhere) minOf(words.lastIndex, 2) else minOf(words.lastIndex, start + 1)
        for (i in start..last) {
            if (isNameToken(words[i].value, sensitivity)) return detection(words[i].value, text.substring(words[i].range.last + 1))
        }
        return null
    }

    private fun looksLikeMention(trailing: String?): Boolean {
        if (trailing == null) return false
        val t = trailing.trim().lowercase()
        if (mentionTail.containsMatchIn(t)) return true
        val words = word.findAll(t).map { it.value }.toList()
        if (words.size > MAX_TAIL_WORDS) return true                          // a story, not a command
        return words.firstOrNull() in latinMentionTail
    }

    private fun fuzzyLatin(token: String, sensitivity: WakeSensitivity): Boolean {
        if (token.length !in 5..8) return false
        val limit = if (sensitivity == WakeSensitivity.SENSITIVE) 2 else 1
        return levenshtein(token, "jarvis") <= limit
    }

    private fun fuzzyKorean(token: String): Boolean {
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
