package com.jarvis.assistant

import com.jarvis.assistant.ai.AiReplyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyParserTest {
    @Test
    fun parsesPlainReply() {
        val r = AiReplyParser.parse("""{"speech":"Certainly.","subtitle":"물론입니다.","action":null}""")
        assertEquals("Certainly.", r.speech)
        assertEquals("물론입니다.", r.subtitle)
        assertNull(r.action)
        assertTrue(r.wellFormed)
    }

    @Test
    fun parsesActionWithParams() {
        val r = AiReplyParser.parse(
            """{"speech":"Opening YouTube.","subtitle":"유튜브를 엽니다.","action":{"type":"OPEN_APP","package":"com.google.android.youtube"}}""",
        )
        assertEquals("OPEN_APP", r.action?.type)
        assertEquals("com.google.android.youtube", r.action?.param("package"))
    }

    @Test
    fun numericParamsBecomeStrings() {
        val r = AiReplyParser.parse(
            """{"speech":"Done.","subtitle":"완료","action":{"type":"SET_ALARM","hour":8,"minutes":30}}""",
        )
        assertEquals("8", r.action?.param("hour"))
        assertEquals("30", r.action?.param("minutes"))
    }

    @Test
    fun stripsCodeFencesAndSurroundingText() {
        val r = AiReplyParser.parse("Sure!\n```json\n{\"speech\":\"Hi.\",\"subtitle\":\"안녕하세요.\",\"action\":null}\n```")
        assertEquals("Hi.", r.speech)
        assertTrue(r.wellFormed)
    }

    @Test
    fun nullTypedActionIsIgnored() {
        val r = AiReplyParser.parse("""{"speech":"Hi.","subtitle":"안녕","action":{"type":"none"}}""")
        assertNull(r.action)
    }

    @Test
    fun malformedEnglishFallsBackToSpeech() {
        val r = AiReplyParser.parse("I'm doing well, thank you.")
        assertFalse(r.wellFormed)
        assertEquals("I'm doing well, thank you.", r.speech)
    }

    @Test
    fun malformedKoreanFallsBackToSubtitle() {
        val r = AiReplyParser.parse("오늘은 맑습니다.")
        assertFalse(r.wellFormed)
        assertEquals("오늘은 맑습니다.", r.subtitle)
        assertTrue(r.speech.isNotBlank())
    }

    @Test
    fun emptyOutputStillProducesReply() {
        val r = AiReplyParser.parse("   ")
        assertFalse(r.wellFormed)
        assertNotNull(r.speech)
    }
}
