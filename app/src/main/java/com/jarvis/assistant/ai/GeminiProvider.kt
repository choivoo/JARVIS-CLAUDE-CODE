package com.jarvis.assistant.ai

import com.jarvis.assistant.data.model.AiProviderType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Google Gemini `generateContent` REST API. */
class GeminiProvider : AIProvider {
    override val type = AiProviderType.GEMINI

    override suspend fun complete(config: AiConfig, turns: List<ChatTurn>): String {
        // Google retires model names from time to time (HTTP 404); fall through to current aliases.
        val models = listOf(config.model) + FALLBACK_MODELS.filter { it != config.model }
        var last: AiException? = null
        for (model in models) {
            try {
                return completeWith(config.copy(model = model), turns)
            } catch (e: AiException) {
                last = e
                if (e.kind != AiError.BAD_RESPONSE || !e.message.orEmpty().contains("HTTP 404")) throw e
            }
        }
        throw last ?: AiException(AiError.BAD_RESPONSE, "No model available")
    }

    private suspend fun completeWith(config: AiConfig, turns: List<ChatTurn>): String {
        val key = config.apiKey?.trim().orEmpty()
        if (key.isEmpty()) throw AiException(AiError.NOT_CONFIGURED, "API key missing")

        val system = turns.filter { it.role == ChatTurn.SYSTEM }.joinToString("\n\n") { it.content }
        val contents = JSONArray()
        turns.filter { it.role != ChatTurn.SYSTEM }.forEach { turn ->
            contents.put(
                JSONObject()
                    .put("role", if (turn.role == ChatTurn.ASSISTANT) "model" else "user")
                    .put("parts", JSONArray().put(JSONObject().put("text", turn.content))),
            )
        }
        val payload = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig",
                JSONObject().put("responseMimeType", "application/json").put("temperature", 0.6)
                    .put("maxOutputTokens", 600).also { gen ->
                        // 2.5 Flash models "think" by default, which burns free quota and adds latency.
                        if (config.model.contains("2.5") && config.model.contains("flash")) {
                            gen.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
                        }
                    },
            )
        if (system.isNotEmpty()) {
            payload.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))),
            )
        }
        val request = Request.Builder()
            .url("${config.endpoint.trimEnd('/')}/models/${config.model}:generateContent")
            .header("x-goog-api-key", key)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val body = execute(request).requireSuccess()
        return try {
            JSONObject(body).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts").getJSONObject(0).optString("text")
        } catch (e: JSONException) {
            throw AiException(AiError.BAD_RESPONSE, "Unexpected response shape")
        }
    }

    private companion object {
        val FALLBACK_MODELS = listOf("gemini-flash-lite-latest", "gemini-2.5-flash-lite", "gemini-flash-latest", "gemini-2.5-flash")
    }
}
