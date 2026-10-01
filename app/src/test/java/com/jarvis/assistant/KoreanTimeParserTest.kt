package com.jarvis.assistant

import com.jarvis.assistant.command.KoreanTimeParser
import com.jarvis.assistant.command.ParsedTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class KoreanTimeParserTest {
    private val noon = LocalTime.of(12, 0)

    @Test
    fun morningAndAfternoon() {
        assertEquals(ParsedTime(8, 0), KoreanTimeParser.parse("내일 오전 8시에 알람 맞춰줘", noon))
        assertEquals(ParsedTime(15, 30), KoreanTimeParser.parse("오후 3시 30분", noon))
        assertEquals(ParsedTime(0, 0), KoreanTimeParser.parse("오전 12시", noon))
        assertEquals(ParsedTime(12, 0), KoreanTimeParser.parse("오후 12시", noon))
    }

    @Test
    fun halfHourAndColon() {
        assertEquals(ParsedTime(7, 30), KoreanTimeParser.parse("아침 7시 반", noon))
        assertEquals(ParsedTime(6, 45), KoreanTimeParser.parse("오전 6:45", noon))
        assertEquals(ParsedTime(18, 0), KoreanTimeParser.parse("저녁 6시", noon))
    }

    @Test
    fun ambiguousHourPicksNextOccurrence() {
        assertEquals(ParsedTime(15, 0), KoreanTimeParser.parse("3시에 깨워줘", noon))
        assertEquals(ParsedTime(8, 0), KoreanTimeParser.parse("8시에 깨워줘", LocalTime.of(20, 0)))
    }

    @Test
    fun twentyFourHourInput() {
        assertEquals(ParsedTime(19, 0), KoreanTimeParser.parse("19시", noon))
    }

    @Test
    fun noTimeReturnsNull() {
        assertNull(KoreanTimeParser.parse("알람 맞춰줘", noon))
    }
}
