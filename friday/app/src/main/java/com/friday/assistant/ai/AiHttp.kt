package com.friday.assistant.ai

import com.friday.assistant.util.Http
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Shared POST-JSON helper that maps transport and HTTP errors to [AiException]. */
internal object AiHttp {
    private val JSON = "application/json; charset=utf-8".toMediaType()

    suspend fun postJson(
        client: OkHttpClient,
        url: String,
        body: String,
        headers: Map<String, String>,
    ): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).apply {
            headers.forEach { (k, v) -> header(k, v) }
        }.build()
        try {
            client.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> text
                    resp.code == 401 || resp.code == 403 -> throw AiException.InvalidKey()
                    resp.code == 429 -> throw AiException.RateLimited()
                    resp.code == 400 && text.contains("API key", ignoreCase = true) -> throw AiException.InvalidKey()
                    else -> throw AiException.Server(resp.code, errorDetail(text))
                }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiException.NoInternet()
        } catch (e: SocketTimeoutException) {
            throw AiException.Timeout()
        } catch (e: IOException) {
            throw AiException.NoInternet()
        }
    }

    /** Provider error text (never contains the key); shortened so it fits on screen. */
    fun errorDetail(body: String): String = try {
        val e = org.json.JSONObject(body).opt("error")
        (if (e is org.json.JSONObject) e.optString("message") else e?.toString()).orEmpty().replace(Regex("\\s+"), " ").take(140)
    } catch (x: Exception) { "" }

    fun client(): OkHttpClient = Http.client
}
