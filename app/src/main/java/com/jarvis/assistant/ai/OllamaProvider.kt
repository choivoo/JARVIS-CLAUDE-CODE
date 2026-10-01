package com.jarvis.assistant.ai

import com.jarvis.assistant.data.model.AiProviderType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Ollama (or any server speaking the Ollama `/api/chat` protocol) for local LLMs. */
class OllamaProvider : AIProvider {
    override val type = AiProviderType.OLLAMA

    override suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String {
        val messages = JSONArray()
        turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("stream", false)
            .put("format", "json")
            .put("options", JSONObject().put("temperature", 0.6))
        val builder = Request.Builder()
            .url(config.endpoint.trimEnd('/') + "/api/chat")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        config.apiKey?.trim()?.takeIf { it.isNotEmpty() }?.let { builder.header("Authorization", "Bearer $it") }
        val body = execute(builder.build()).requireSuccess()
        return try {
            JSONObject(body).getJSONObject("message").optString("content")
        } catch (e: JSONException) {
            throw AiException(AiError.BAD_RESPONSE, "Unexpected response shape")
        }
    }
}
