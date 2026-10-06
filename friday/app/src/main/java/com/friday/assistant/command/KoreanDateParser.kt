package com.friday.assistant.command

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Spoken Korean dates: 오늘, 내일, 모레, 금요일, 다음 주 월요일, 10월 9일. */
object KoreanDateParser {
    private val days = mapOf(
        "월" to DayOfWeek.MONDAY, "화" to DayOfWeek.TUESDAY, "수" to DayOfWeek.WEDNESDAY, "목" to DayOfWeek.THURSDAY,
        "금" to DayOfWeek.FRIDAY, "토" to DayOfWeek.SATURDAY, "일" to DayOfWeek.SUNDAY,
    )
    private val weekday = Regex("""(다음\s*주|담주|이번\s*주)?\s*([월화수목금토일])요일""")
    private val monthDay = Regex("""(\d{1,2})\s*월\s*(\d{1,2})\s*일""")

    data class Match(val date: LocalDate, val range: IntRange)

    fun parse(input: String, today: LocalDate): Match? {
        monthDay.find(input)?.let { m ->
            val month = m.groupValues[1].toInt(); val day = m.groupValues[2].toInt()
            val d = runCatching { LocalDate.of(today.year, month, day) }.getOrNull() ?: return null
            return Match(if (d.isBefore(today)) d.plusYears(1) else d, m.range)
        }
        weekday.find(input)?.let { m ->
            val target = days.getValue(m.groupValues[2])
            val prefix = m.groupValues[1].replace(" ", "")
            val d = when {
                prefix == "다음주" || prefix == "담주" ->
                    today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(target))
                prefix == "이번주" -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).with(TemporalAdjusters.nextOrSame(target))
                else -> today.with(TemporalAdjusters.next(target))
            }
            return Match(d, m.range)
        }
        for ((word, offset) in listOf("모레" to 2L, "내일" to 1L, "오늘" to 0L, "글피" to 3L)) {
            val i = input.indexOf(word)
            if (i >= 0) return Match(today.plusDays(offset), i until i + word.length)
        }
        return null
    }
}
