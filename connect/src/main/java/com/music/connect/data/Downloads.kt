package com.music.connect.data

import android.content.Context
import android.util.Log
import com.music.connect.net.ApiJson
import com.music.connect.net.ApiTrack
import com.music.connect.net.ServerApi
import com.music.connect.net.ServerException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class Download(val track: ApiTrack, val file: File?, val bytes: Long, val description: String, val state: String, val error: String?) {
    val done: Boolean get() = state == DownloadRow.STATE_DONE && file != null
}

/**
 * Songs kept on the phone for listening offline. Each is fetched whole at the download quality,
 * into a part file that's picked up where it stopped (a satellite link can drop mid-song) and
 * renamed once complete; a converted stream can't be resumed, so it starts again.
 */
class Downloads(
    private val context: Context,
    private val db: ConnectDb,
    private val api: ServerApi,
    private val http: OkHttpClient,
    private val prefs: Prefs,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called when there's something new to fetch. */
    private val onQueued: () -> Unit = {},
) {
    private val dao = db.downloads()
    private val folder: File get() = File(context.filesDir, "music").also { it.mkdirs() }

    val all: Flow<List<Download>> = dao.all().map { rows -> rows.mapNotNull { it.toDownload() } }

    /** The downloaded keys, for marking songs that play offline. */
    val doneKeys: Flow<Set<String>> = dao.all().map { rows -> rows.filter { it.state == DownloadRow.STATE_DONE }.map { it.key }.toSet() }

    suspend fun fileFor(track: ApiTrack): File? =
        dao.get(track.key)?.takeIf { it.state == DownloadRow.STATE_DONE }?.file?.let(::File)?.takeIf { it.exists() }

    suspend fun queue(tracks: List<ApiTrack>) {
        var added = false
        for (track in tracks.filter { it.isSong && !it.isMissing }) {
            val existing = dao.get(track.key)
            if (existing != null && existing.state != DownloadRow.STATE_FAILED) continue
            dao.upsert(DownloadRow(track.key, ApiJson.encodeToString(ApiTrack.serializer(), track), updatedAt = clock()))
            added = true
        }
        if (added) onQueued()
    }

    suspend fun remove(track: ApiTrack) {
        dao.get(track.key)?.file?.let { File(it).delete() }
        partFile(track.key).delete()
        dao.delete(track.key)
    }

    /** Fetches everything queued; returns false if something should be tried again later. */
    suspend fun runQueue(): Boolean {
        var allDone = true
        for (row in dao.queued()) {
            val track = runCatching { ApiJson.decodeFromString(ApiTrack.serializer(), row.track) }.getOrNull()
            if (track == null) {
                dao.delete(row.key)
                continue
            }
            try {
                fetch(row, track)
            } catch (e: ServerException) {
                // The server can't provide it (gone from every source): give up on it.
                dao.upsert(row.copy(state = DownloadRow.STATE_FAILED, error = e.message, updatedAt = clock()))
            } catch (e: IOException) {
                Log.i(TAG, "Download of ${track.title} stopped: ${e.message}")
                allDone = false
                break // The connection went: the rest can wait too.
            }
        }
        return allDone
    }

    private suspend fun fetch(row: DownloadRow, track: ApiTrack) = withContext(Dispatchers.IO) {
        val kbps = prefs.current().kbpsDownload
        val resolved = api.resolve(track, kbps, thorough = true)
        val part = partFile(row.key)
        val resumeFrom = if (resolved.seekable && part.exists()) part.length() else 0L
        if (resumeFrom == 0L) part.delete()
        val request = Request.Builder().url(api.absolute(resolved.streamUrl)).apply {
            if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-")
        }.build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw ServerException(response.code, "The server answered HTTP ${response.code}")
            val append = resumeFrom > 0 && response.code == 206
            val body = response.body ?: throw IOException("Nothing came back")
            java.io.FileOutputStream(part, append).use { out -> body.byteStream().use { it.copyTo(out, 64 * 1024) } }
        }
        val extension = resolved.extension.ifBlank { "audio" }.filter { it.isLetterOrDigit() }.take(5)
        val file = File(folder, "${hash(row.key)}.$extension")
        if (!part.renameTo(file)) throw IOException("Couldn't save the download")
        dao.upsert(
            row.copy(
                file = file.path,
                bytes = file.length(),
                description = resolved.description,
                state = DownloadRow.STATE_DONE,
                error = null,
                updatedAt = clock(),
                track = ApiJson.encodeToString(ApiTrack.serializer(), track),
            ),
        )
    }

    private fun partFile(key: String) = File(folder, "${hash(key)}.part")

    private fun DownloadRow.toDownload(): Download? = runCatching {
        Download(ApiJson.decodeFromString(ApiTrack.serializer(), track), file?.let(::File), bytes, description, state, error)
    }.getOrNull()

    companion object {
        private const val TAG = "Downloads"

        fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}
