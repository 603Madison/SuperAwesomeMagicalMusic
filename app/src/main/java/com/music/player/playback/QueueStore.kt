package com.music.player.playback

import android.util.Log
import com.music.player.model.Track
import com.music.player.net.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File

/** The queue as it was, so playback can pick up where it left off (e.g. when the car connects). */
@Serializable
data class SavedQueue(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val savedAt: Long,
)

/** Keeps the last queue in a small file. */
class QueueStore(private val file: File) {
    suspend fun save(queue: SavedQueue) = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(AppJson.encodeToString(SavedQueue.serializer(), queue))
            if (!temp.renameTo(file)) {
                file.writeText(temp.readText())
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Couldn't save the queue", it) }
        Unit
    }

    suspend fun load(): SavedQueue? = withContext(Dispatchers.IO) {
        runCatching {
            if (file.exists()) AppJson.decodeFromString(SavedQueue.serializer(), file.readText()) else null
        }.getOrNull()?.takeIf { it.tracks.isNotEmpty() }
    }

    private companion object {
        const val TAG = "QueueStore"
    }
}
