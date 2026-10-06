package com.friday.assistant.ai

import org.json.JSONArray
import org.json.JSONObject

/** Local/LAN Ollama server (`/api/chat`). No key required. */
class OllamaProvider(
    private val endpoint: String,
    private val model: String,
    private val post: suspend (url: String, body: String, headers: Map<String, String>) -> String =
        { url, body, headers -> AiHttp.postJson(AiHttp.client(), url, body, headers) },
) : AIProvider {
    override val name = "Ollama"

    override suspend fun complete(messages: List<ChatMessage>): String {
        if (endpoint.isBlank() || model.isBlank()) throw AiException.NotConfigured("Endpoint or model is missing")
        val arr = JSONArray()
        messages.forEach {
            arr.put(JSONObject().put("role", it.role.name.lowercase()).put("content", it.content))
        }
        val body = JSONObject().put("model", model).put("messages", arr).put("stream", false).put("format", "json")
        val raw = post(endpoint.trimEnd('/') + "/api/chat", body.toString(), emptyMap())
        return try {
            JSONObject(raw).getJSONObject("message").getString("content")
        } catch (e: Exception) {
            throw AiException.BadResponse("Unexpected AI response format")
        }
    }
}
