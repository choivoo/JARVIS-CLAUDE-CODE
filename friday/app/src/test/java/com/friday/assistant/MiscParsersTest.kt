package com.friday.assistant

import com.friday.assistant.command.Answer
import com.friday.assistant.command.AppResolver
import com.friday.assistant.command.CommandType
import com.friday.assistant.command.ContactResolver
import com.friday.assistant.command.ResolvedApp
import com.friday.assistant.command.YesNoParser
import com.friday.assistant.search.SearchParsers
import com.friday.assistant.util.EnglishNumbers
import com.friday.assistant.wake.WakeWordMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscParsersTest {
    @Test fun wakeWordVariants() {
        assertEquals("", WakeWordMatcher.match("FRIDAY")!!.remainder)
        assertEquals("", WakeWordMatcher.match("프라이데이")!!.remainder)
        assertEquals("날씨 알려줘", WakeWordMatcher.match("프라이데이 날씨 알려줘")!!.remainder)
        assertEquals("what time is it", WakeWordMatcher.match("Hey Friday, what time is it")!!.remainder)
        assertNull(WakeWordMatcher.match("오늘 날씨 어때"))
    }

    @Test fun yesNo() {
        listOf("네", "응", "그래 해줘", "좋아", "yes", "Yes please").forEach { assertEquals(it, Answer.YES, YesNoParser.parse(it)) }
        listOf("아니", "아니야 취소", "no", "취소해줘").forEach { assertEquals(it, Answer.NO, YesNoParser.parse(it)) }
        assertEquals(Answer.UNKNOWN, YesNoParser.parse("오늘 날씨"))
        assertEquals(Answer.UNKNOWN, YesNoParser.parse(""))
    }

    @Test fun englishNumbers() {
        assertEquals("seventy-two", EnglishNumbers.words(72))
        assertEquals("zero", EnglishNumbers.words(0))
        assertEquals("one hundred", EnglishNumbers.words(100))
        assertEquals("minus five", EnglishNumbers.words(-5))
        assertEquals("fifteen", EnglishNumbers.words(15))
    }

    private val apps = listOf(
        ResolvedApp("YouTube", "com.google.android.youtube"), ResolvedApp("카카오톡", "com.kakao.talk"),
        ResolvedApp("Calculator", "com.android.calculator2"), ResolvedApp("Samsung Camera", "com.sec.camera"),
    )

    @Test fun appResolverAliasesAndLabels() {
        assertEquals("com.google.android.youtube", AppResolver.match("유튜브", apps)?.packageName)
        assertEquals("com.kakao.talk", AppResolver.match("카톡", apps)?.packageName)
        assertEquals("com.android.calculator2", AppResolver.match("calculator", apps)?.packageName)
        assertEquals("com.sec.camera", AppResolver.match("camera", apps)?.packageName)
        assertNull(AppResolver.match("없는앱", apps))
        assertNull(AppResolver.match("  ", apps))
    }

    @Test fun contactAliases() {
        assertTrue(ContactResolver.candidates("엄마").containsAll(listOf("엄마", "어머니", "mom")))
        assertEquals(listOf("철수"), ContactResolver.candidates("철수한테"))
    }

    @Test fun commandTypeAllowList() {
        assertEquals(CommandType.OPEN_APP, CommandType.parse("open_app"))
        assertNull(CommandType.parse("EXEC_SHELL"))
        assertNull(CommandType.parse(null))
        assertTrue(CommandType.CALL_CONTACT_REQUEST.needsConfirmation)
        assertTrue(CommandType.promptCatalog().contains("SET_ALARM"))
    }

    @Test fun duckDuckGoHtml() {
        val html = """
            <div class="result"><a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa&rut=x">Genshin <b>Impact</b> 5.0</a>
            <a class="result__snippet" href="x">New version &amp; events <b>live</b></a></div>
            <div class="result"><a class="result__a" href="https://b.example/">Second</a><a class="result__snippet" href="y">Second snippet</a></div>
        """.trimIndent()
        val hits = SearchParsers.parseDuckDuckGoHtml(html, 5)
        assertEquals(2, hits.size)
        assertEquals("Genshin Impact 5.0", hits[0].title)
        assertEquals("New version & events live", hits[0].snippet)
        assertEquals("https://example.com/a", hits[0].url)
        assertEquals(1, SearchParsers.parseDuckDuckGoHtml(html, 1).size)
        assertTrue(SearchParsers.parseDuckDuckGoHtml("<html></html>", 5).isEmpty())
    }

    @Test fun wikipediaJson() {
        val json = """{"query":{"search":[{"title":"원신","snippet":"<span class=\"searchmatch\">원신</span>은 게임이다"}]}}"""
        val hits = SearchParsers.parseWikipedia(json, "ko.wikipedia.org", 3)
        assertEquals(1, hits.size)
        assertEquals("원신은 게임이다", hits[0].snippet)
        assertNotNull(hits[0].url)
        assertTrue(SearchParsers.parseWikipedia("{}", "x", 3).isEmpty())
    }
}
