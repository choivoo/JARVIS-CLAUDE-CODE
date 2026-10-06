package com.jarvis.assistant.util

import android.util.Log

/** Logging wrapper that scrubs anything resembling an API key before it reaches logcat. */
object JLog {
    private val secretPatterns = listOf(
        Regex("sk-[A-Za-z0-9_\\-]{8,}"),
        Regex("AIza[0-9A-Za-z_\\-]{10,}"),
        Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}"),
        Regex("(?i)(api[-_]?key|xi-api-key|authorization)\\s*[:=]\\s*\\S+"),
    )

    fun redact(message: String): String =
        secretPatterns.fold(message) { acc, regex -> regex.replace(acc, "[REDACTED]") }

    fun d(tag: String, message: String) {
        Log.d(tag, redact(message))
    }

    fun w(tag: String, message: String, t: Throwable? = null) {
        Log.w(tag, redact(message + (t?.let { " (${it.javaClass.simpleName})" } ?: "")))
    }

    fun e(tag: String, message: String, t: Throwable? = null) {
        Log.e(tag, redact(message + (t?.let { " (${it.javaClass.simpleName}: ${it.message})" } ?: "")))
    }
}
