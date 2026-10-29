package com.music.player.net

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

val AppJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

class HttpException(val code: Int, message: String) : IOException(message)

object Http {
    const val USER_AGENT = "Music/1.0 (Android)"

    fun createClient(): OkHttpClient = OkHttpClient.Builder()
        // Satellite links have multi-second latency; be patient before giving up.
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            if (request.header("User-Agent") != null) {
                chain.proceed(request)
            } else {
                chain.proceed(request.newBuilder().header("User-Agent", USER_AGENT).build())
            }
        })
        .build()

    /** Accepts "host:port", "http://host/", "https://host/sub/" and returns "scheme://host[:port][/sub]". */
    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}

/** Executes the call without blocking the caller's thread; cancelling the coroutine cancels the call. */
suspend fun OkHttpClient.execute(request: Request): Response = suspendCancellableCoroutine { cont ->
    val call = newCall(request)
    cont.invokeOnCancellation { call.cancel() }
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }

        override fun onResponse(call: Call, response: Response) {
            cont.resume(response) { _, _, _ -> response.close() }
        }
    })
}

/** Executes the request and returns the body, throwing [HttpException] for non-2xx responses. */
suspend fun OkHttpClient.fetchString(request: Request): String = execute(request).use { response ->
    val body = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
        throw HttpException(response.code, "${request.method} ${request.url.encodedPath} failed: HTTP ${response.code} ${body.take(200)}")
    }
    body
}
