package com.jarvis.assistant.ai

import org.json.JSONException
import org.json.JSONObject

/** Turns raw model output into an [AiReply]. Never throws: malformed output gets a graceful fallback. */
object AiReplyParser {
    private val hangul = Regex("[\\u3131-\\u318E\\uAC00-\\uD7A3]")

    fun parse(raw: String): AiReply {
        val obj = extractJson(raw)
        if (obj != null) {
            val speech = obj.optString("speech").trim()
            val subtitle = obj.optString("subtitle").trim()
            if (speech.isNotEmpty() || subtitle.isNotEmpty()) {
                return AiReply(
                    speech = speech.ifEmpty { subtitle },
                    subtitle = subtitle.ifEmpty { speech },
                    action = parseAction(obj.opt("action")),
                )
            }
        }
        return fallback(raw)
    }

    private fun parseAction(value: Any?): AiAction? {
        val obj = value as? JSONObject ?: return null
        val type = obj.optString("type").trim()
        if (type.isEmpty() || type.equals("null", ignoreCase = true) || type.equals("none", ignoreCase = true)) {
            return null
        }
        val params = linkedMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key == "type") continue
            val v = obj.opt(key)
            if (v == null || v == JSONObject.NULL) continue
            if (key == "params" && v is JSONObject) {
                val inner = v.keys()
                while (inner.hasNext()) {
                    val k = inner.next()
                    val iv = v.opt(k)
                    if (iv != null && iv != JSONObject.NULL) params[k] = iv.toString()
                }
            } else {
                params[key] = v.toString()
            }
        }
        return AiAction(type, params)
    }

    private fun extractJson(raw: String): JSONObject? {
        val text = raw.trim()
            .removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```")
            .trim()
        tryParse(text)?.let { return it }
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start in 0 until end) return tryParse(text.substring(start, end + 1))
        return null
    }

    private fun tryParse(text: String): JSONObject? = try {
        JSONObject(text)
    } catch (e: JSONException) {
        null
    }

    private fun fallback(raw: String): AiReply {
        val text = raw.trim()
        if (text.isEmpty()) {
            return AiReply(
                speech = "I'm sorry, I didn't catch that.",
                subtitle = "죄송합니다, 잘 이해하지 못했습니다.",
                wellFormed = false,
            )
        }
        val shown = if (text.length > 400) text.take(400) + "…" else text
        return if (hangul.containsMatchIn(text)) {
            AiReply(
                speech = "Here is my answer, sir. Please see the subtitle.",
                subtitle = shown,
                wellFormed = false,
            )
        } else {
            AiReply(speech = shown, subtitle = shown, wellFormed = false)
        }
    }
}
