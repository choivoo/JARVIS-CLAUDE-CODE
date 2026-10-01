package com.jarvis.assistant

import com.jarvis.assistant.command.LocalIntentParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalTime

class LocalIntentParserTest {
    private val now = LocalTime.of(9, 0)
    private fun type(text: String) = LocalIntentParser.parse(text, now)?.action?.type

    @Test
    fun deviceCommands() {
        assertEquals("GET_TIME", type("JARVIS, 지금 시간 알려줘"))
        assertEquals("GET_BATTERY", type("내 배터리 얼마나 남았어?"))
        assertEquals("FLASHLIGHT_ON", type("손전등 켜줘"))
        assertEquals("FLASHLIGHT_OFF", type("손전등 꺼줘"))
        assertEquals("VOLUME_UP", type("볼륨 올려줘"))
        assertEquals("VOLUME_DOWN", type("볼륨 낮춰줘"))
        assertEquals("OPEN_SETTINGS", type("설정 열어줘"))
    }

    @Test
    fun volumePercent() {
        val a = LocalIntentParser.parse("볼륨 50퍼센트로 설정해", now)?.action
        assertEquals("SET_VOLUME", a?.type)
        assertEquals("50", a?.param("level"))
    }

    @Test
    fun alarm() {
        val a = LocalIntentParser.parse("내일 오전 8시에 알람 맞춰줘", now)?.action
        assertEquals("SET_ALARM", a?.type)
        assertEquals("8", a?.param("hour"))
        assertEquals("0", a?.param("minutes"))
    }

    @Test
    fun alarmWithoutTimeAsksQuestion() {
        val r = LocalIntentParser.parse("알람 설정해줘", now)
        assertNotNull(r)
        assertNull(r?.action)
    }

    @Test
    fun youtube() {
        val search = LocalIntentParser.parse("유튜브에서 원신 검색해줘", now)?.action
        assertEquals("SEARCH_YOUTUBE", search?.type)
        assertEquals("원신", search?.param("query"))
        val open = LocalIntentParser.parse("유튜브 열어줘", now)?.action
        assertEquals("OPEN_APP", open?.type)
        assertEquals("유튜브", open?.param("app"))
        assertEquals("OPEN_APP", type("유튜브 뮤직 열어줘"))
    }

    @Test
    fun weather() {
        val a = LocalIntentParser.parse("오늘 서울 날씨 알려줘", now)?.action
        assertEquals("WEATHER", a?.type)
        assertEquals("Seoul", a?.param("city"))
        assertEquals("today", a?.param("day"))
        assertEquals("tomorrow", LocalIntentParser.parse("내일 날씨 알려줘", now)?.action?.param("day"))
    }

    @Test
    fun webSearch() {
        val a = LocalIntentParser.parse("구글에서 코틀린 코루틴 검색해줘", now)?.action
        assertEquals("SEARCH_WEB", a?.type)
        assertEquals("코틀린 코루틴", a?.param("query"))
    }

    @Test
    fun music() {
        assertEquals("MUSIC_PLAY", type("음악 재생해줘"))
        assertEquals("MUSIC_NEXT", type("다음 곡"))
        assertEquals("MUSIC_PAUSE", type("음악 일시정지"))
    }

    @Test
    fun smallTalkIsNotACommand() {
        assertNull(LocalIntentParser.parse("너는 누구니", now))
    }
}
