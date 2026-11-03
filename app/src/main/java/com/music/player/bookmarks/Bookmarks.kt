package com.music.player.bookmarks

import android.util.Log
import com.music.player.model.Track
import com.music.player.net.AppJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File
import java.util.UUID

/** A song bookmarked (e.g. from Android Auto while driving), waiting to be put in a playlist. */
@Serializable
data class Bookmark(
    val id: String,
    val track: Track,
    /**
     * Heard on SiriusXM rather than played from the library: when saved, the Jellyfin copy is
     * looked for first (and the artist requested from Lidarr), as when saving a heard song.
     */
    val heard: Boolean,
    val savedAtMs: Long,
    /** Where it was heard, e.g. "Alt Nation" or "Noah Kahan Radio". */
    val heardOn: String? = null,
    /** What was playing when it was bookmarked, so pressing the button again takes it back. */
    val playingKey: String? = null,
)

/** Bookmarks waiting for a playlist, kept in a small JSON file so they survive restarts. */
class Bookmarks(private val file: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val mutex = Mutex()
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Bookmark>> = _items.asStateFlow()

    suspend fun add(track: Track, heard: Boolean, heardOn: String?, playingKey: String?): Bookmark {
        val bookmark = Bookmark(UUID.randomUUID().toString(), track, heard, clock(), heardOn, playingKey)
        update { it + bookmark }
        return bookmark
    }

    suspend fun remove(id: String) {
        update { list -> list.filterNot { it.id == id } }
    }

    /** The bookmark made while [playingKey] was playing, if any. */
    fun forPlaying(playingKey: String): Bookmark? = _items.value.lastOrNull { it.playingKey == playingKey }

    private suspend fun update(change: (List<Bookmark>) -> List<Bookmark>): Unit = mutex.withLock {
        val updated = change(_items.value)
        _items.value = updated
        withContext(Dispatchers.IO) {
            runCatching {
                val temp = File(file.parentFile, "${file.name}.tmp")
                temp.writeText(AppJson.encodeToString(SERIALIZER, updated))
                if (!temp.renameTo(file)) {
                    file.writeText(temp.readText())
                    temp.delete()
                }
            }.onFailure { Log.w(TAG, "Couldn't save bookmarks", it) }
        }
        Unit
    }

    private fun load(): List<Bookmark> = runCatching {
        if (file.exists()) AppJson.decodeFromString(SERIALIZER, file.readText()) else emptyList()
    }.onFailure { Log.w(TAG, "Couldn't read bookmarks", it) }.getOrDefault(emptyList())

    private companion object {
        const val TAG = "Bookmarks"
        val SERIALIZER = ListSerializer(Bookmark.serializer())
    }
}
