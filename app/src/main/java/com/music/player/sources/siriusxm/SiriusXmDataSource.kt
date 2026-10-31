package com.music.player.sources.siriusxm

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Serves SiriusXM's encrypted HLS streams to ExoPlayer: media playlists are rewritten so each
 * `#EXT-X-KEY` points at `sxmkey://<guid>`, and those keys are fetched from SiriusXM with the
 * signed-in session. Xtra channels play from `sxmxtra://` playlists the client stitches together
 * (see [XtraStream]). Everything else passes straight through to [upstream].
 */
@OptIn(UnstableApi::class)
class SiriusXmDataSource(
    private val upstream: DataSource,
    private val client: SiriusXmClient,
) : DataSource {
    private var served: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        return when {
            uri.scheme == SiriusXmParser.KEY_SCHEME -> {
                val guid = uri.host ?: throw IOException("Malformed key URI $uri")
                val key = try {
                    runBlocking { client.key(guid) }
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Couldn't fetch the SiriusXM stream key: ${e.message}", e)
                }
                serve(dataSpec, key)
            }
            uri.scheme == SiriusXmClient.XTRA_SCHEME -> {
                val (type, channelId) = uri.pathSegments.takeIf { it.size == 2 }
                    ?.let { it[0] to it[1].removeSuffix(".m3u8") }
                    ?: throw IOException("Malformed Xtra URI $uri")
                val playlist = try {
                    runBlocking { client.xtraPlaylist(type, channelId) }
                } catch (e: IOException) {
                    throw e
                } catch (e: Exception) {
                    throw IOException("Couldn't load the SiriusXM Xtra channel: ${e.message}", e)
                }
                serve(dataSpec, playlist.toByteArray())
            }
            uri.path?.endsWith(".m3u8") == true -> {
                val playlist = readAll(dataSpec).decodeToString()
                // Live playlists reload every few seconds; log each one once.
                if (logged.add(uri.path.orEmpty())) {
                    client.recordDiagnostics("playlist ${uri.lastPathSegment}", playlist.lineSequence().take(40).joinToString("\n"))
                }
                serve(dataSpec, SiriusXmParser.rewriteKeys(playlist, client.fallbackKeyId).toByteArray())
            }
            else -> {
                served = upstream
                upstream.open(dataSpec)
            }
        }
    }

    private fun readAll(dataSpec: DataSpec): ByteArray {
        try {
            upstream.open(dataSpec)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = upstream.read(buffer, 0, buffer.size)
                if (read == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        } finally {
            upstream.close()
        }
    }

    private fun serve(dataSpec: DataSpec, bytes: ByteArray): Long {
        val source = ByteArrayDataSource(bytes)
        served = source
        return source.open(dataSpec.buildUpon().setPosition(0).setLength(C.LENGTH_UNSET.toLong()).build())
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        served?.read(buffer, offset, length) ?: throw IOException("Not opened")

    override fun getUri(): Uri? = served?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = served?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            served?.close()
        } finally {
            served = null
        }
    }

    private companion object {
        val logged: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }

    class Factory(private val upstream: DataSource.Factory, private val client: SiriusXmClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = SiriusXmDataSource(upstream.createDataSource(), client)
    }
}
