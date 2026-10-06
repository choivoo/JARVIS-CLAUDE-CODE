package com.friday.assistant

import com.friday.assistant.ai.AiReplyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AiReplyParserTest {
    @Test fun parsesPlainJson() {
        val r = AiReplyParser.parse("""{"speech":"Your battery is at seventy-two percent.","subtitle":"현재 배터리는 72%입니다.","action":null}""")
        assertEquals("Your battery is at seventy-two percent.", r.speech)
        assertEquals("현재 배터리는 72%입니다.", r.subtitle)
        assertNull(r.action)
    }

    @Test fun parsesActionWithParams() {
        val r = AiReplyParser.parse("""{"speech":"Opening YouTube.","subtitle":"유튜브를 실행합니다.","action":{"type":"open_app","target":"youtube"}}""")
        assertEquals("OPEN_APP", r.action?.type)
        assertEquals("youtube", r.action?.target)
    }

    @Test fun parsesNestedParams() {
        val r = AiReplyParser.parse("""{"speech":"x","subtitle":"y","action":{"type":"SET_ALARM","params":{"hour":7,"minute":30}}}""")
        assertEquals("7", r.action?.params?.get("hour"))
        assertEquals("30", r.action?.params?.get("minute"))
    }

    @Test fun stripsMarkdownFences() {
        val r = AiReplyParser.parse("```json\n{\"speech\":\"Hi.\",\"subtitle\":\"안녕하세요.\",\"action\":null}\n```")
        assertEquals("Hi.", r.speech)
    }

    @Test fun extractsJsonSurroundedByText() {
        val r = AiReplyParser.parse("Sure! {\"speech\":\"Done {ok}.\",\"subtitle\":\"완료\",\"action\":null} hope it helps")
        assertEquals("Done {ok}.", r.speech)
    }

    @Test fun nullActionStringIsNoAction() {
        val r = AiReplyParser.parse("""{"speech":"a","subtitle":"b","action":{"type":"NONE"}}""")
        assertNull(r.action)
    }

    @Test fun missingSubtitleReusesSpeech() {
        val r = AiReplyParser.parse("""{"speech":"Hello."}""")
        assertEquals("Hello.", r.subtitle)
    }

    @Test fun malformedEnglishTextIsSpokenAsIs() {
        val r = AiReplyParser.parse("It is sunny today.")
        assertEquals("It is sunny today.", r.speech)
    }

    @Test fun malformedKoreanTextIsShownNotSpoken() {
        val r = AiReplyParser.parse("오늘은 맑습니다")
        assertEquals("오늘은 맑습니다", r.subtitle)
        assertTrue(r.speech.isNotBlank() && r.speech.none { it in '가'..'힣' })
    }

    @Test fun brokenJsonNeverThrows() {
        val r = AiReplyParser.parse("{\"speech\": \"cut off")
        assertNotNull(r)
        assertTrue(r.speech.isNotBlank())
    }

    @Test fun emptyInputGivesApology() {
        val r = AiReplyParser.parse("   ")
        assertTrue(r.speech.contains("sorry", ignoreCase = true))
    }
}
