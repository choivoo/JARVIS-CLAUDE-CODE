package com.jarvis.assistant

import com.jarvis.assistant.speech.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WakeWordMatcherTest {
    @Test
    fun matchesEnglishAndKorean() {
        assertNotNull(WakeWordMatcher.match("Jarvis"))
        assertNotNull(WakeWordMatcher.match("hey jarvis"))
        assertNotNull(WakeWordMatcher.match("자비스"))
        assertNotNull(WakeWordMatcher.match("자비스야"))
    }

    @Test
    fun ignoresOtherSpeech() {
        assertNull(WakeWordMatcher.match("오늘 날씨 알려줘"))
        assertNull(WakeWordMatcher.match("서비스 센터"))
    }

    @Test
    fun extractsTrailingCommand() {
        assertEquals("지금 시간 알려줘", WakeWordMatcher.match("자비스, 지금 시간 알려줘")?.trailingText)
        assertEquals("지금 시간 알려줘", WakeWordMatcher.match("자비스야 지금 시간 알려줘")?.trailingText)
        assertNull(WakeWordMatcher.match("자비스")?.trailingText)
    }

    @Test
    fun prefersCandidateWithCommand() {
        val hit = WakeWordMatcher.match(listOf("자비스", "자비스 유튜브 열어줘"))
        assertEquals("유튜브 열어줘", hit?.trailingText)
    }
}
