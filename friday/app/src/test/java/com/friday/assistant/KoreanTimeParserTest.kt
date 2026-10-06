package com.friday.assistant

import com.friday.assistant.command.KoreanTimeParser
import com.friday.assistant.command.ParsedTime
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KoreanTimeParserTest {
    private val noon = LocalDateTime.of(2026, 10, 6, 12, 0)
    private fun p(s: String, now: LocalDateTime = noon) = KoreanTimeParser.parse(s, now)

    @Test fun tomorrowMorning() = assertEquals(ParsedTime(7, 0, 1), p("내일 오전 7시에 알람 맞춰줘"))
    @Test fun afternoonWithMinutes() = assertEquals(ParsedTime(15, 30, 0), p("오후 3시 30분"))
    @Test fun halfHour() = assertEquals(ParsedTime(6, 30, 0), p("오전 6시 반"))
    @Test fun evening() = assertEquals(ParsedTime(20, 15, 0), p("저녁 8시 15분"))
    @Test fun twentyFourHour() = assertEquals(ParsedTime(19, 45, 0), p("19시 45분에"))
    @Test fun colonFormat() = assertEquals(ParsedTime(7, 30, 1), p("내일 07:30"))
    @Test fun dayAfterTomorrow() = assertEquals(ParsedTime(9, 0, 2), p("모레 오전 9시"))
    @Test fun midnightNoon() {
        assertEquals(ParsedTime(0, 0, 0), p("오전 12시"))
        assertEquals(ParsedTime(12, 0, 0), p("오후 12시"))
    }
    @Test fun nativeKoreanNumeral() = assertEquals(ParsedTime(19, 0, 0), p("저녁 일곱 시"))
    @Test fun nightTen() = assertEquals(ParsedTime(22, 0, 0), p("밤 10시"))
    @Test fun relativeMinutes() = assertEquals(ParsedTime(12, 30, 0), p("30분 뒤에 알람"))
    @Test fun relativeCrossesMidnight() =
        assertEquals(ParsedTime(0, 30, 1), p("1시간 후", LocalDateTime.of(2026, 10, 6, 23, 30)))
    @Test fun noMarkerTodayPicksNextOccurrence() {
        assertEquals(ParsedTime(15, 0, 0), p("3시", noon))                                   // 3 AM passed, 3 PM next
        assertEquals(ParsedTime(7, 0, 0), p("7시", LocalDateTime.of(2026, 10, 6, 5, 0)))    // 7 AM still ahead
    }
    @Test fun noMarkerTomorrowHeuristic() {
        assertEquals(ParsedTime(7, 0, 1), p("내일 7시"))
        assertEquals(ParsedTime(15, 0, 1), p("내일 3시"))
    }
    @Test fun noTimeReturnsNull() = assertNull(p("알람 맞춰줘"))
    @Test fun invalidMinuteReturnsNull() = assertNull(p("7시 75분"))
}
