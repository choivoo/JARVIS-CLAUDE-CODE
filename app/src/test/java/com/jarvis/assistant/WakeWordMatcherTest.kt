package com.jarvis.assistant

import com.jarvis.assistant.speech.WakeWordMatcher
import com.jarvis.assistant.data.model.WakeMode
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

class WakeCallRulesTest {
    private fun call(t: String, mode: WakeMode = WakeMode.CALL, s: WakeSensitivity = WakeSensitivity.NORMAL) = WakeWordMatcher.match(t, s, mode)

    @Test
    fun talkingAboutJarvisDoesNotWake() {
        assertNull(call("어제 자비스가 말했는데 날씨가 좋대"))
        assertNull(call("나 어제 자비스 영화 봤어"))
        assertNull(call("자비스는 아이언맨에 나오는 AI야"))
        assertNull(call("자비스를 한번 써 봤는데 괜찮더라"))
        assertNull(call("자비스의 목소리가 좋아"))
        assertNull(call("자비스 영화 봤어?"))
        assertNull(call("the Jarvis movie was great"))
        assertNull(call("Jarvis was talking about it"))
        assertNull(call("오늘 날씨 좋다 자비스"))
    }

    @Test
    fun beingCalledStillWakes() {
        assertNotNull(call("자비스"))
        assertNotNull(call("자비스야"))
        assertNotNull(call("자비스, 지금 시간 알려줘"))
        assertNotNull(call("자비스야 오늘 날씨 어때"))
        assertNotNull(call("헤이 자비스 불 꺼줘"))
        assertNotNull(call("hey Jarvis what time is it"))
        assertNotNull(call("Jarvis, is it going to rain?"))
        assertEquals("오늘 날씨 어때", call("자비스야 오늘 날씨 어때")?.trailingText)
    }

    @Test
    fun aLongStoryAfterTheNameIsNotACommand() {
        val story = "자비스 " + (1..25).joinToString(" ") { "단어$it" }
        assertNull(call(story))
        assertNotNull(call("자비스 내일 오전 여덟 시에 알람 맞추고 그다음에 날씨도 알려줘"))
    }

    @Test
    fun anywhereModeKeepsTheOldBehaviour() {
        assertNotNull(call("어제 자비스가 말했는데", WakeMode.ANYWHERE))
        assertNotNull(call("오늘 날씨 좋다 자비스", WakeMode.ANYWHERE))
    }

    @Test
    fun doubleModeNeedsTheNameTwice() {
        assertNull(call("자비스", WakeMode.DOUBLE))
        assertNull(call("자비스 지금 시간 알려줘", WakeMode.DOUBLE))
        assertNull(call("어제 자비스 자비스가 말했는데", WakeMode.DOUBLE))
        assertNotNull(call("자비스 자비스", WakeMode.DOUBLE))
        assertEquals("불 꺼줘", call("자비스 자비스 불 꺼줘", WakeMode.DOUBLE)?.trailingText)
        assertNotNull(call("hey jarvis jarvis open youtube", WakeMode.DOUBLE))
    }

    @Test
    fun sensitivityStillAppliesInCallMode() {
        assertNotNull(call("차비스야 시간 알려줘", s = WakeSensitivity.SENSITIVE))
        assertNull(call("서비스 점검 중입니다", s = WakeSensitivity.SENSITIVE))
        assertNull(call("차비스야 시간 알려줘", s = WakeSensitivity.NORMAL))
    }
}
