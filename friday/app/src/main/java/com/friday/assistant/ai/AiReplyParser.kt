package com.friday.assistant.ai

import org.json.JSONObject

/** Tolerant parser for the AI JSON. Never throws: malformed output degrades to a plain-text reply. */
object AiReplyParser {
    fun parse(raw: String): AiReply {
        val text = raw.trim()
        val obj = extractObject(text)
        if (obj != null) {
            val speech = obj.optString("speech", "").trim()
            val subtitle = obj.optString("subtitle", "").trim()
            val action = parseAction(obj.opt("action"))
            if (speech.isNotEmpty() || subtitle.isNotEmpty() || action != null) {
                return AiReply(
                    speech = speech.ifEmpty { subtitle },
                    subtitle = subtitle.ifEmpty { speech },
                    action = action,
                )
            }
        }
        return fallback(text)
    }

    private fun fallback(text: String): AiReply {
        val cleaned = stripFences(text).trim()
        if (cleaned.isEmpty()) {
            return AiReply("I'm sorry, I didn't get a usable answer.", "죄송합니다. 올바른 답변을 받지 못했습니다.")
        }
        // Plain text answer: Korean text can only be shown, English text can be spoken as well.
        val hasHangul = cleaned.any { it in '가'..'힣' }
        return if (hasHangul) {
            AiReply("I have an answer, but I could not format it for speech. Please read the subtitle.", cleaned)
        } else {
            AiReply(cleaned, cleaned)
        }
    }

    private fun stripFences(s: String): String =
        s.removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()

    private fun extractObject(text: String): JSONObject? {
        val s = stripFences(text)
        runCatching { return JSONObject(s) }
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until s.length) {
            val c = s[i]
            if (inString) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
                continue
            }
            when (c) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull()
                }
            }
        }
        return null
    }

    private fun parseAction(value: Any?): AiAction? {
        val obj = value as? JSONObject ?: return null
        val type = obj.optString("type", "").trim().uppercase()
        if (type.isEmpty() || type == "NULL" || type == "NONE") return null
        val params = linkedMapOf<String, String>()
        for (key in obj.keys()) {
            if (key == "type") continue
            val v = obj.opt(key)
            if (v == null || v == JSONObject.NULL) continue
            params[key] = v.toString()
        }
        // Accept {"type":"X","params":{...}} too.
        (obj.opt("params") as? JSONObject)?.let { p ->
            for (key in p.keys()) params.putIfAbsent(key, p.optString(key))
            params.remove("params")
        }
        return AiAction(type, params)
    }
}
