package com.friday.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Works with OpenAI and any server that speaks `/chat/completions` (Groq, OpenRouter, LM Studio, ...). */
class OpenAICompatibleProvider(
    private val endpoint: String,
    private val model: String,
    private val apiKey: String,
    private val post: suspend (url: String, body: String, headers: Map<String, String>) -> String =
        { url, body, headers -> AiHttp.postJson(AiHttp.client(), url, body, headers) },
) : AIProvider {
    override val name = "OpenAI-compatible"

    override suspend fun complete(messages: List<ChatMessage>): String {
        if (apiKey.isBlank()) throw AiException.NotConfigured("API key is missing")
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("role", it.role.name.lowercase()).put("content", it.content))
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", arr)
            .put("temperature", 0.4)
            .put("max_tokens", 400)
            .put("response_format", JSONObject().put("type", "json_object"))
            .toString()
        val raw = post(
            endpoint.trimEnd('/') + "/chat/completions",
            body,
            mapOf("Authorization" to "Bearer $apiKey"),
        )
        return parseResponse(raw)
    }

    companion object {
        fun parseResponse(raw: String): String = try {
            JSONObject(raw).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
        } catch (e: Exception) {
            throw AiException.BadResponse("Unexpected AI response format")
        }
    }
}
