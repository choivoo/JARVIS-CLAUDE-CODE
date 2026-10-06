package com.friday.assistant

import com.friday.assistant.ai.AiException
import com.friday.assistant.ai.ChatMessage
import com.friday.assistant.ai.GeminiProvider
import com.friday.assistant.ai.OllamaProvider
import com.friday.assistant.ai.OpenAICompatibleProvider
import com.friday.assistant.ai.Role
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AiProvidersTest {
    private val msgs = listOf(ChatMessage(Role.SYSTEM, "sys"), ChatMessage(Role.USER, "hi"), ChatMessage(Role.ASSISTANT, "yo"), ChatMessage(Role.USER, "more"))

    @Test fun openAiRequestAndResponse() = runTest {
        var url = ""; var body = ""; var headers = emptyMap<String, String>()
        val p = OpenAICompatibleProvider("https://x.test/v1/", "m1", "KEY") { u, b, h -> url = u; body = b; headers = h; """{"choices":[{"message":{"content":"{\"speech\":\"ok\"}"}}]}""" }
        assertEquals("{\"speech\":\"ok\"}", p.complete(msgs))
        assertEquals("https://x.test/v1/chat/completions", url)
        assertEquals("Bearer KEY", headers["Authorization"])
        val j = JSONObject(body)
        assertEquals("m1", j.getString("model")); assertEquals(4, j.getJSONArray("messages").length())
        assertEquals("json_object", j.getJSONObject("response_format").getString("type"))
        assertFalse(body.contains("KEY"))
    }

    @Test fun geminiKeepsKeyOutOfUrlAndMapsRoles() = runTest {
        var url = ""; var body = ""; var headers = emptyMap<String, String>()
        val p = GeminiProvider("https://g.test/v1beta", "gem", "SECRET") { u, b, h -> url = u; body = b; headers = h; """{"candidates":[{"content":{"parts":[{"text":"hello"}]}}]}""" }
        assertEquals("hello", p.complete(msgs))
        assertEquals("https://g.test/v1beta/models/gem:generateContent", url)
        assertFalse(url.contains("SECRET"))
        assertEquals("SECRET", headers["x-goog-api-key"])
        val j = JSONObject(body)
        assertEquals("model", j.getJSONArray("contents").getJSONObject(1).getString("role"))
        assertTrue(j.has("systemInstruction"))
    }

    @Test fun ollamaNeedsNoKey() = runTest {
        val p = OllamaProvider("http://10.0.0.2:11434", "llama") { u, _, h -> assertTrue(h.isEmpty()); assertEquals("http://10.0.0.2:11434/api/chat", u); """{"message":{"content":"hey"}}""" }
        assertEquals("hey", p.complete(msgs))
    }

    @Test fun missingKeyIsNotConfigured() = runTest {
        try { OpenAICompatibleProvider("e", "m", "") { _, _, _ -> "" }.complete(msgs); fail() } catch (e: AiException.NotConfigured) { }
        try { GeminiProvider("e", "m", " ") { _, _, _ -> "" }.complete(msgs); fail() } catch (e: AiException.NotConfigured) { }
    }

    @Test fun garbageResponseIsBadResponse() = runTest {
        try { OpenAICompatibleProvider("e", "m", "k") { _, _, _ -> "<html>" }.complete(msgs); fail() } catch (e: AiException.BadResponse) { }
        try { GeminiProvider("e", "m", "k") { _, _, _ -> """{"candidates":[]}""" }.complete(msgs); fail() } catch (e: AiException.BadResponse) { }
    }
}
