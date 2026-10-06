package com.friday.assistant.command

import com.friday.assistant.calendar.CalendarExecutors
import com.friday.assistant.core.ContextEngine
import com.friday.assistant.core.Spoken
import com.friday.assistant.util.EnglishNumbers

/**
 * Answers follow-ups about what was just listed, on the device, from the ContextEngine
 * ("첫 번째 일정 몇 시야?" right after "오늘 일정 알려줘"). Returns null when the sentence is not such a follow-up.
 */
object FollowUpResolver {
    private val ordinal = Regex("""(첫|첫째|1|한|두|둘째|2|세|셋째|3|네|넷째|4|다섯|5|마지막)\s*(?:번째|째|번)?\s*(?:일정|스케줄)""")

    fun resolve(text: String, context: ContextEngine): Spoken? {
        val m = ordinal.find(text) ?: return null
        val events = context.events()
        if (events.isEmpty()) return null
        val word = m.groupValues[1]
        val idx = when (word) {
            "첫", "첫째", "1", "한" -> 0; "두", "둘째", "2" -> 1; "세", "셋째", "3" -> 2; "네", "넷째", "4" -> 3; "다섯", "5" -> 4
            else -> events.lastIndex
        }
        val e = events.getOrNull(idx) ?: return Spoken("I only have ${EnglishNumbers.words(events.size)} on the list.", "목록에는 일정이 ${events.size}개뿐입니다.")
        val n = idx + 1
        val label = if (word == "마지막") "The last one" else "Number ${EnglishNumbers.words(n)}"
        val labelKo = if (word == "마지막") "마지막 일정" else "${n}번째 일정"
        return Spoken("$label is at ${CalendarExecutors.timeWords(e)}.", "$labelKo: ${CalendarExecutors.timeKo(e)} ${e.title}")
    }
}
