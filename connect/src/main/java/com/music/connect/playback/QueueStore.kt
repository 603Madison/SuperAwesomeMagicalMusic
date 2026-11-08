package com.music.connect.playback

import androidx.media3.common.Player
import com.music.connect.net.ApiJson
import kotlinx.serialization.Serializable
import java.io.File

/** The queue and where playback was, kept so a car's play button can carry on after a restart. */
class QueueStore(folder: File) {
    private val file = File(folder, "queue.json")

    @Serializable
    data class Saved(val ids: List<String>, val index: Int, val positionMs: Long)

    fun save(player: Player) {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.take(MAX)
        val saved = Saved(ids, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition.coerceAtLeast(0))
        runCatching {
            val temp = File(file.path + ".tmp")
            temp.writeText(ApiJson.encodeToString(Saved.serializer(), saved))
            temp.renameTo(file)
        }
    }

    fun load(): Saved? = runCatching { ApiJson.decodeFromString(Saved.serializer(), file.readText()) }.getOrNull()?.takeIf { it.ids.isNotEmpty() }

    private companion object {
        const val MAX = 2_000
    }
}
