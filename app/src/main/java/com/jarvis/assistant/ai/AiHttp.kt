package com.jarvis.assistant.ai

import com.jarvis.assistant.util.Http
import com.jarvis.assistant.util.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal class RawResponse(val code: Int, val body: String)

/** Executes the request off the main thread and maps transport failures onto [AiException]. */
internal suspend fun execute(request: Request): RawResponse = withContext(Dispatchers.IO) {
    try {
        Http.client.await(request).use { response ->
            RawResponse(response.code, response.body?.string().orEmpty())
        }
    } catch (e: SocketTimeoutException) {
        throw AiException(AiError.TIMEOUT, "The AI service timed out")
    } catch (e: UnknownHostException) {
        throw AiException(AiError.NETWORK, "No connection")
    } catch (e: IOException) {
        throw AiException(AiError.NETWORK, "Network error: ${e.javaClass.simpleName}")
    }
}

internal fun RawResponse.requireSuccess(): String {
    if (code in 200..299) return body
    val detail = try {
        JSONObject(body).let { obj ->
            obj.optJSONObject("error")?.optString("message")
                ?: obj.optString("error").takeIf { it.isNotEmpty() }
                ?: ""
        }
    } catch (e: Exception) {
        ""
    }.take(200)
    throw when (code) {
        401, 403 -> AiException(AiError.AUTH, "Authentication failed ($code) $detail")
        429 -> AiException(AiError.RATE_LIMIT, "Rate limited $detail")
        in 500..599 -> AiException(AiError.SERVER, "Server error $code $detail")
        else -> AiException(AiError.BAD_RESPONSE, "HTTP $code $detail")
    }
}
