package com.jarvis.assistant.ai

import com.jarvis.assistant.data.model.AiProviderType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Works with OpenAI, Azure-style gateways, OpenRouter, LM Studio, llama.cpp server, vLLM, ... */
class OpenAICompatibleProvider(override val type: AiProviderType = AiProviderType.OPENAI_COMPATIBLE) : AIProvider {

    override suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String {
        val key = config.apiKey?.trim().orEmpty()
        if (key.isEmpty() && isHosted(config.endpoint)) {
            throw AiException(AiError.NOT_CONFIGURED, "API key missing")
        }
        val first = send(config, key, turns, jsonMode = true)
        // Some compatible servers reject response_format; retry once without it.
        val body = if (first.code == 400 || first.code == 422) {
            send(config, key, turns, jsonMode = false).requireSuccess()
        } else {
            first.requireSuccess()
        }
        return try {
            JSONObject(body).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content")
        } catch (e: JSONException) {
            throw AiException(AiError.BAD_RESPONSE, "Unexpected response shape")
        }
    }

    private suspend fun send(config: AiConfig, key: String, turns: List<ChatTurn>, jsonMode: Boolean): RawResponse {
        val messages = JSONArray()
        turns.forEach { messages.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject()
            .put("model", config.model)
            .put("messages", messages)
            .put("temperature", 0.6)
            .put("max_tokens", 600)
        if (jsonMode) payload.put("response_format", JSONObject().put("type", "json_object"))
        val builder = Request.Builder()
            .url(config.endpoint.trimEnd('/') + "/chat/completions")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
        if (key.isNotEmpty()) builder.header("Authorization", "Bearer $key")
        return execute(builder.build())
    }

    private companion object {
        /** Hosted services always need a key; local servers (LM Studio, vLLM, ...) may not. */
        val HOSTED = listOf("api.openai.com", "api.groq.com", "api.cerebras.ai", "openrouter.ai")

        fun isHosted(endpoint: String) = HOSTED.any { endpoint.contains(it) }
    }
}
