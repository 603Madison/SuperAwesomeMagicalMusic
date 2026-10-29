package com.music.player.downloads

import com.music.player.net.HttpException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext

/**
 * Downloads a stream to a file. Range-capable servers are fetched in chunks, each retried on
 * its own if the connection drops (YouTube also throttles long single requests); live
 * transcodes are fetched in one request.
 */
class ChunkedDownloader(private val client: OkHttpClient) {

    suspend fun download(
        url: String,
        headers: Map<String, String>,
        target: File,
        useRanges: Boolean,
        onProgress: suspend (downloaded: Long, total: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        RandomAccessFile(target, "rw").use { out ->
            out.setLength(0)
            if (!useRanges) {
                execute(url, headers, range = null).use { response ->
                    ensureSuccess(response)
                    copy(response, out, 0L, response.body?.contentLength()?.takeIf { it > 0 }, onProgress)
                }
                return@use
            }
            var offset = 0L
            var total: Long? = null
            while (total == null || offset < total) {
                val chunk = withChunkRetries { fetchChunk(url, headers, out, offset, total, onProgress) }
                when (chunk) {
                    is Chunk.Partial -> {
                        total = chunk.total ?: total
                        offset += chunk.bytes
                        // Unknown total size: a short (or empty) chunk means we reached the end.
                        if (total == null && chunk.bytes < CHUNK_BYTES) total = offset
                        if (chunk.bytes == 0L) total = offset
                    }
                    is Chunk.Whole -> {
                        offset = chunk.bytes
                        total = offset
                    }
                    Chunk.End -> total = offset
                }
            }
        }
    }

    private sealed interface Chunk {
        data class Partial(val bytes: Long, val total: Long?) : Chunk
        /** The server ignored the Range header and sent the whole file. */
        data class Whole(val bytes: Long) : Chunk
        data object End : Chunk
    }

    private suspend fun fetchChunk(
        url: String,
        headers: Map<String, String>,
        out: RandomAccessFile,
        offset: Long,
        knownTotal: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Chunk = execute(url, headers, range = offset until offset + CHUNK_BYTES).use {
        when (it.code) {
            206 -> {
                val total = parseTotal(it.header("Content-Range")) ?: knownTotal
                Chunk.Partial(copy(it, out, offset, total, onProgress), total)
            }
            200 -> {
                out.setLength(0)
                val size = it.body?.contentLength()?.takeIf { len -> len > 0 }
                Chunk.Whole(copy(it, out, 0L, size, onProgress))
            }
            416 -> Chunk.End
            else -> {
                ensureSuccess(it)
                Chunk.End
            }
        }
    }

    /** A dropped connection (common on satellite) only costs a retry of the current chunk. */
    private suspend fun <T> withChunkRetries(block: suspend () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (e: IOException) {
                attempt++
                val clientError = e is HttpException && e.code in 400..499
                if (clientError || attempt >= CHUNK_ATTEMPTS) throw e
                delay(RETRY_DELAY_MS * attempt)
            }
        }
    }

    private fun execute(url: String, headers: Map<String, String>, range: LongRange?): Response {
        val request = Request.Builder().url(url).apply {
            headers.forEach { (name, value) -> header(name, value) }
            range?.let { header("Range", "bytes=${it.first}-${it.last}") }
        }.build()
        return client.newCall(request).execute()
    }

    private fun ensureSuccess(response: Response) {
        if (!response.isSuccessful) throw HttpException(response.code, "Download failed: HTTP ${response.code}")
    }

    private suspend fun copy(
        response: Response,
        out: RandomAccessFile,
        startOffset: Long,
        total: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long {
        val body = response.body ?: return 0L
        out.seek(startOffset)
        val buffer = ByteArray(64 * 1024)
        var read = 0L
        var lastReport = 0L
        body.byteStream().use { input ->
            while (true) {
                coroutineContext.ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                read += n
                val now = System.currentTimeMillis()
                if (now - lastReport > PROGRESS_INTERVAL_MS) {
                    lastReport = now
                    onProgress(startOffset + read, total)
                }
            }
        }
        onProgress(startOffset + read, total)
        return read
    }

    companion object {
        const val CHUNK_BYTES = 4L * 1024 * 1024
        private const val CHUNK_ATTEMPTS = 4
        private const val RETRY_DELAY_MS = 2_000L
        private const val PROGRESS_INTERVAL_MS = 500L

        /** "bytes 0-1023/4096" -> 4096; "bytes 0-1023/ *" -> null */
        fun parseTotal(contentRange: String?): Long? =
            contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
    }
}
