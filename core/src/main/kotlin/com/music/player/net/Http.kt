package com.music.player.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
    val USER_AGENT: String get() = "${com.music.player.AppInfo.name}/${com.music.player.AppInfo.version} (${com.music.player.AppInfo.platform})"

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
        .addInterceptor(YouTubeMediaRequests)
        .addNetworkInterceptor(DropCredentialsOnRedirect)
        .build()

    /**
     * Asks YouTube's media servers for audio the way NewPipe's own player does: a POST with a
     * two-byte body, a request number, and no compression. YouTube's servers stall or refuse
     * requests that don't look like its players' (and NewPipe's library is built around these).
     */
    object YouTubeMediaRequests : Interceptor {
        private val number = AtomicInteger()
        private val BODY = byteArrayOf(0x78, 0)

        fun applies(request: Request): Boolean =
            request.method == "GET" && request.url.host.endsWith(".googlevideo.com") && request.url.encodedPath.startsWith("/videoplayback")

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            if (!applies(request)) return chain.proceed(request)
            val url = request.url.newBuilder().apply {
                if (request.url.queryParameter("rn") == null) addQueryParameter("rn", number.incrementAndGet().toString())
            }.build()
            return chain.proceed(
                request.newBuilder()
                    .url(url)
                    .header("Accept-Encoding", "identity")
                    .post(BODY.toRequestBody(null))
                    .build(),
            )
        }
    }

    /**
     * OkHttp drops the Authorization header when a redirect leads to another host, but not other
     * credential headers. Lidarr's API key and Qobuz's token are sent in their own headers, so
     * a redirect elsewhere (to a sign-in page in front of a home server, say) would carry them
     * along; this removes them whenever the request has left the host it was made for.
     */
    object DropCredentialsOnRedirect : Interceptor {
        val HEADERS = listOf("X-Api-Key", "X-User-Auth-Token", "X-Emby-Token", "X-MediaBrowser-Token", "Authorization")

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val original = chain.call().request().url
            if (request.url.host == original.host) return chain.proceed(request)
            val stripped = request.newBuilder().apply { HEADERS.forEach(::removeHeader) }.build()
            return chain.proceed(stripped)
        }
    }

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

/**
 * Sends [request] and hands the response to [handle], both on the IO dispatcher: reading a
 * response body is network I/O too, which Android forbids on the main thread. Cancelling the
 * coroutine cancels the call.
 */
suspend fun <T> OkHttpClient.call(request: Request, handle: (Response) -> T): T =
    withContext(Dispatchers.IO) { enqueueAndAwait(request).use(handle) }

private suspend fun OkHttpClient.enqueueAndAwait(request: Request): Response = suspendCancellableCoroutine { cont ->
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
suspend fun OkHttpClient.fetchString(request: Request): String = call(request) { response ->
    val body = response.body?.string().orEmpty()
    if (!response.isSuccessful) {
        throw HttpException(response.code, "${request.method} ${request.url.encodedPath} failed: HTTP ${response.code} ${body.take(200)}")
    }
    body
}
