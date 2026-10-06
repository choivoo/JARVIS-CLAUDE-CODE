package com.friday.assistant.util

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/** One shared OkHttp client (connection pool, dispatcher). Never log bodies or headers. */
object Http {
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
