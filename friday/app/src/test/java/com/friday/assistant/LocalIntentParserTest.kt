package com.friday.assistant

import com.friday.assistant.command.CommandType
import com.friday.assistant.command.LocalIntentParser
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIntentParserTest {
    private val now = LocalDateTime.of(2026, 10, 6, 9, 0)
    private fun type(s: String) = LocalIntentParser.parse(s, now)?.type

    @Test fun time() { assertEquals(CommandType.GET_TIME, type("지금 몇 시야?")); assertEquals(CommandType.GET_TIME, type("시간 알려줘")) }
    @Test fun date() { assertEquals(CommandType.GET_DATE, type("오늘 며칠이야")); assertEquals(CommandType.GET_DATE, type("오늘 무슨 요일이야?")) }
    @Test fun battery() = assertEquals(CommandType.GET_BATTERY, type("배터리 얼마나 남았어?"))
    @Test fun flashlight() {
        assertEquals(CommandType.FLASHLIGHT_ON, type("손전등 켜줘"))
        assertEquals(CommandType.FLASHLIGHT_OFF, type("플래시 꺼줘"))
    }
    @Test fun volume() {
        assertEquals(CommandType.VOLUME_UP, type("볼륨 올려줘"))
        assertEquals(CommandType.VOLUME_DOWN, type("소리 줄여줘"))
        val c = LocalIntentParser.parse("볼륨 40으로 맞춰줘", now)!!
        assertEquals(CommandType.SET_VOLUME, c.type); assertEquals("40", c.param("percent"))
    }
    @Test fun media() {
        assertEquals(CommandType.NEXT_MEDIA, type("다음 곡 틀어줘"))
        assertEquals(CommandType.PREVIOUS_MEDIA, type("이전 곡"))
        assertEquals(CommandType.PAUSE_MEDIA, type("음악 멈춰줘"))
        assertEquals(CommandType.PLAY_MEDIA, type("음악 재생해줘"))
    }
    @Test fun openApp() {
        val c = LocalIntentParser.parse("카카오톡 열어줘", now)!!
        assertEquals(CommandType.OPEN_APP, c.type); assertEquals("카카오톡", c.param("target"))
        assertEquals(CommandType.OPEN_YOUTUBE, type("유튜브 열어줘"))
        assertEquals(CommandType.OPEN_SETTINGS, type("설정 열어줘"))
    }
    @Test fun youtubeSearch() {
        val c = LocalIntentParser.parse("유튜브에서 포켓몬 검색해줘", now)!!
        assertEquals(CommandType.YOUTUBE_SEARCH, c.type); assertEquals("포켓몬", c.param("query"))
    }
    @Test fun alarm() {
        val c = LocalIntentParser.parse("내일 오전 7시에 알람 맞춰줘", now)!!
        assertEquals(CommandType.SET_ALARM, c.type)
        assertEquals("7", c.param("hour")); assertEquals("0", c.param("minute")); assertEquals("1", c.param("dayOffset"))
    }
    @Test fun openAiNeededSentencesAreNotMatched() {
        assertNull(type("오늘 비 올 것 같아?"))
        assertNull(type("원신 최신 업데이트 검색해줘"))
        assertNull(type("엄마한테 전화해줘"))
        assertNull(type(""))
    }
}
