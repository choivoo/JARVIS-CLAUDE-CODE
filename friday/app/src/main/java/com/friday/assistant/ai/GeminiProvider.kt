package com.friday.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Google Gemini `generateContent`. The key travels in a header, never in the URL. */
class GeminiProvider(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val post: suspend (url: String, body: String, headers: Map<String, String>) -> String =
        { url, body, headers -> AiHttp.postJson(AiHttp.client(), url, body, headers) },
) : AIProvider {
    override val name = "Gemini"

    override suspend fun complete(messages: List<ChatMessage>): String {
        if (apiKey.isBlank()) throw AiException.NotConfigured("API key is missing")
        val system = messages.filter { it.role == Role.SYSTEM }.joinToString("\n\n") { it.content }
        val contents = JSONArray()
        messages.filter { it.role != Role.SYSTEM }.forEach {
            contents.put(
                JSONObject()
                    .put("role", if (it.role == Role.ASSISTANT) "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", it.content))),
            )
        }
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.4).put("maxOutputTokens", 400)
                    .put("responseMimeType", "application/json"),
            )
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        val raw = post(
            endpoint.trimEnd('/') + "/models/$model:generateContent",
            body.toString(),
            mapOf("x-goog-api-key" to apiKey),
        )
        return parseResponse(raw)
    }

    companion object {
        fun parseResponse(raw: String): String = try {
            val parts = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            buildString { for (i in 0 until parts.length()) append(parts.getJSONObject(i).optString("text")) }
                .ifBlank { throw AiException.BadResponse("Empty AI response") }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException.BadResponse("Unexpected AI response format")
        }
    }
}
