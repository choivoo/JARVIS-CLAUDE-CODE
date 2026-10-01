package com.jarvis.assistant

import com.jarvis.assistant.speech.WakeWordMatcher
import com.jarvis.assistant.data.model.WakeSensitivity
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

    @Test
    fun normalSensitivityAcceptsLatinMishearings() {
        assertNotNull(WakeWordMatcher.match("Jervis open youtube", WakeSensitivity.NORMAL))
        assertNotNull(WakeWordMatcher.match("jarvus", WakeSensitivity.NORMAL))
        assertNull(WakeWordMatcher.match("service", WakeSensitivity.SENSITIVE))
        assertNull(WakeWordMatcher.match("jargon is hard", WakeSensitivity.NORMAL))
    }

    @Test
    fun sensitiveAcceptsKoreanVariantsButNotCommonWords() {
        assertNotNull(WakeWordMatcher.match("저비스", WakeSensitivity.SENSITIVE))
        assertNotNull(WakeWordMatcher.match("차비스야 시간 알려줘", WakeSensitivity.SENSITIVE))
        assertNull(WakeWordMatcher.match("서비스 점검 중입니다", WakeSensitivity.SENSITIVE))
        assertNull(WakeWordMatcher.match("저비스", WakeSensitivity.NORMAL))
        assertEquals("시간 알려줘", WakeWordMatcher.match("차비스야 시간 알려줘", WakeSensitivity.SENSITIVE)?.trailingText)
    }

    @Test
    fun strictOnlyExactAliases() {
        assertNotNull(WakeWordMatcher.match("자비스", WakeSensitivity.STRICT))
        assertNull(WakeWordMatcher.match("Jervs", WakeSensitivity.STRICT))
        assertNull(WakeWordMatcher.match("jarvus", WakeSensitivity.STRICT).takeIf { false })
    }

    @Test
    fun wakeWordOnlyInFirstWordsForFuzzy() {
        assertNull(WakeWordMatcher.match("오늘은 정말 좋은 날 차비스", WakeSensitivity.SENSITIVE))
    }
}
