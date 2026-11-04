package com.music.player.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException

/**
 * Reads streams that need it in short byte ranges, one request per range, as YouTube's own
 * players do. YouTube slows down (or lets stall) a single long request for a whole song,
 * especially one held open while the buffer is full, so a song could load slower than it plays;
 * fresh short requests each start at full speed, and a stalled one only costs that range.
 * Other streams pass straight through to [upstream].
 */
@OptIn(UnstableApi::class)
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkBytes: Long = CHUNK_BYTES,
    private val shouldChunk: (Uri) -> Boolean = ::isYouTubeStream,
) : DataSource {
    private var spec: DataSpec? = null
    private var chunking = false
    private var upstreamOpen = false

    /** Where the next byte comes from, in the whole resource. */
    private var position = 0L

    /** Where the requested data ends (exclusive), once known. */
    private var end = C.LENGTH_UNSET.toLong()
    private var chunkRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        chunking = shouldChunk(dataSpec.uri)
        if (!chunking) {
            upstreamOpen = true
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        position = dataSpec.position
        end = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.position + dataSpec.length
            else -> declaredLength(dataSpec.uri) ?: C.LENGTH_UNSET.toLong()
        }
        if (end != C.LENGTH_UNSET.toLong() && position >= end) throw IOException("Nothing to read at $position of $end")
        openChunk()
        return if (end != C.LENGTH_UNSET.toLong()) end - dataSpec.position else C.LENGTH_UNSET.toLong()
    }

    /** Opens the next range; false when there's nothing left. */
    private fun openChunk(): Boolean {
        val spec = spec ?: throw IOException("Not open")
        val length = if (end == C.LENGTH_UNSET.toLong()) chunkBytes else minOf(chunkBytes, end - position)
        if (length <= 0) return false
        val opened = upstream.open(spec.subrange(position - spec.position, length))
        upstreamOpen = true
        if (end == C.LENGTH_UNSET.toLong()) {
            totalFromContentRange(upstream.responseHeaders)?.let { total -> end = total }
        }
        chunkRemaining = if (opened != C.LENGTH_UNSET.toLong()) minOf(opened, length) else length
        // Asked for a whole range and got less: that's where the stream ends.
        if (end == C.LENGTH_UNSET.toLong() && opened != C.LENGTH_UNSET.toLong() && opened < length) end = position + opened
        return chunkRemaining > 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!chunking) return upstream.read(buffer, offset, length)
        if (length == 0) return 0
        while (true) {
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            if (chunkRemaining == 0L) {
                closeUpstream()
                if (!openChunk()) return C.RESULT_END_OF_INPUT
            }
            val read = upstream.read(buffer, offset, minOf(length.toLong(), chunkRemaining).toInt())
            if (read == C.RESULT_END_OF_INPUT) {
                if (end == C.LENGTH_UNSET.toLong()) {
                    // The server didn't say how long the stream is, and it has ended.
                    end = position
                    return C.RESULT_END_OF_INPUT
                }
                // The range ended early: ask for the rest from here.
                chunkRemaining = 0
                continue
            }
            position += read
            chunkRemaining -= read
            return read
        }
    }

    override fun getUri(): Uri? = if (chunking) spec?.uri else upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        closeUpstream()
        spec = null
        chunking = false
        chunkRemaining = 0
    }

    private fun closeUpstream() {
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val chunkBytes: Long = CHUNK_BYTES) : DataSource.Factory {
        override fun createDataSource(): DataSource = ChunkedDataSource(upstream.createDataSource(), chunkBytes)
    }

    companion object {
        /** About 25 seconds of 160 kbps audio. */
        const val CHUNK_BYTES = 512L * 1024

        fun isYouTubeStream(uri: Uri): Boolean = uri.host?.endsWith(".googlevideo.com") == true

        /** YouTube stream URLs carry the file's length ("clen"). */
        fun declaredLength(uri: Uri): Long? = runCatching { uri.getQueryParameter("clen") }.getOrNull()?.toLongOrNull()?.takeIf { it > 0 }

        /** "bytes 0-524287/3433755" -> 3433755. */
        fun totalFromContentRange(headers: Map<String, List<String>>): Long? =
            headers.entries.firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                ?.value?.firstOrNull()
                ?.substringAfterLast('/')
                ?.trim()
                ?.toLongOrNull()
    }
}
