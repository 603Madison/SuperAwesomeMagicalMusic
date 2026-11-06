package com.music.player.lyrics

import android.util.LruCache
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.net.HttpException
import com.music.player.net.fetchString
import com.music.player.sources.TrackMatcher
import com.music.player.sources.jellyfin.JellyfinSource
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** One line of lyrics; [timeMs] is when it's sung, for synced lyrics. */
data class LyricLine(val timeMs: Long?, val text: String)

data class Lyrics(val lines: List<LyricLine>, val source: String) {
    val synced: Boolean get() = lines.any { it.timeMs != null }

    /** The line being sung at [positionMs] (synced lyrics only), or -1 before the first. */
    fun lineAt(positionMs: Long): Int {
        if (!synced) return -1
        return lines.indexOfLast { it.timeMs != null && it.timeMs <= positionMs }
    }
}

/**
 * Lyrics for a song: from Jellyfin when the song is in your library and Jellyfin has them,
 * otherwise from LRCLIB (lrclib.net), a free, open lyrics database. Only the song's title,
 * artist, album and length are sent, and only when lyrics are opened.
 */
class LyricsRepository(
    private val http: OkHttpClient,
    private val jellyfin: JellyfinSource,
    private val userAgent: String,
    private val baseUrl: String = "https://lrclib.net/",
) {
    private val cache = LruCache<String, Lyrics>(50)
    private val missing = LruCache<String, Boolean>(200)

    /** Lyrics for [track] (the queued song) played from [copy] (the copy playing), or null if none were found. */
    suspend fun find(track: Track, copy: Track? = null): Lyrics? {
        val key = track.key
        cache.get(key)?.let { return it }
        if (missing.get(key) == true) return null
        val jellyfinCopy = listOfNotNull(copy, track).firstOrNull { it.source == SourceType.JELLYFIN }
        val found = jellyfinCopy?.let { runCatching { fromJellyfin(it.sourceId) }.getOrNull() }
            ?: fromLrclib(track)
        if (found == null) missing.put(key, true) else cache.put(key, found)
        return found
    }

    private suspend fun fromJellyfin(itemId: String): Lyrics? {
        val lines = jellyfin.lyrics(itemId)?.lyrics ?: return null
        return Lyrics(lines.map { LyricLine(it.start?.let { ticks -> ticks / 10_000 }, it.text) }, "Jellyfin")
    }

    private suspend fun fromLrclib(track: Track): Lyrics? {
        val title = TrackMatcher.searchTitle(track.title)
        val artist = TrackMatcher.primaryArtist(track.artist)
        if (title.isBlank() || artist.isBlank()) return null
        val exact = "${baseUrl}api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply {
                track.album?.takeIf { it.isNotBlank() }?.let { addQueryParameter("album_name", it) }
                track.durationMs?.takeIf { it > 0 }?.let { addQueryParameter("duration", (it / 1000).toString()) }
            }
            .build()
        val record = try {
            AppJson.decodeFromString<LrclibRecord>(http.fetchString(request(exact)))
        } catch (e: HttpException) {
            if (e.code != 404) throw e
            // No exact match (a different album or length): search, and take the closest length.
            val search = "${baseUrl}api/search".toHttpUrl().newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .build()
            val results = AppJson.decodeFromString(ListSerializer(LrclibRecord.serializer()), http.fetchString(request(search)))
                .filter { TrackMatcher.normalizeTitle(it.trackName.orEmpty()) == TrackMatcher.normalizeTitle(track.title) }
            val seconds = track.durationMs?.div(1000)
            results.minByOrNull { if (seconds == null || it.duration == null) 0.0 else kotlin.math.abs(it.duration - seconds) }
        } ?: return null
        if (record.instrumental) return Lyrics(listOf(LyricLine(null, "♪ Instrumental ♪")), "LRCLIB")
        record.syncedLyrics?.takeIf { it.isNotBlank() }?.let { return Lyrics(parseLrc(it), "LRCLIB") }
        record.plainLyrics?.takeIf { it.isNotBlank() }?.let { plain ->
            return Lyrics(plain.lines().map { LyricLine(null, it) }, "LRCLIB")
        }
        return null
    }

    // LRCLIB asks clients to say who they are.
    private fun request(url: okhttp3.HttpUrl) = Request.Builder().url(url).header("User-Agent", userAgent).build()

    @Serializable
    private data class LrclibRecord(
        @SerialName("trackName") val trackName: String? = null,
        @SerialName("duration") val duration: Double? = null,
        @SerialName("instrumental") val instrumental: Boolean = false,
        @SerialName("plainLyrics") val plainLyrics: String? = null,
        @SerialName("syncedLyrics") val syncedLyrics: String? = null,
    )

    companion object {
        private val TIMESTAMP = Regex("\\[(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?]")

        /** "[01:02.34] Words" lines (a line may carry several timestamps), in time order. */
        fun parseLrc(lrc: String): List<LyricLine> = lrc.lines().flatMap { raw ->
            val stamps = TIMESTAMP.findAll(raw).toList()
            if (stamps.isEmpty()) return@flatMap emptyList()
            val text = raw.substring(stamps.last().range.last + 1).trim()
            stamps.map { stamp ->
                val (minutes, seconds, fraction) = stamp.destructured
                val fractionMs = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100
                    2 -> fraction.toLong() * 10
                    else -> fraction.take(3).toLong()
                }
                LyricLine(minutes.toLong() * 60_000 + seconds.toLong() * 1000 + fractionMs, text)
            }
        }.sortedBy { it.timeMs }
    }
}
