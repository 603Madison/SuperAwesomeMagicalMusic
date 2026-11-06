package com.music.player.playback

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import android.content.Context
import com.music.player.data.CachedCopyDao
import com.music.player.data.CachedCopyEntity
import com.music.player.model.Track
import com.music.player.sources.SourceStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps recently played and upcoming songs on the phone, so a dead zone (or a stretch on
 * satellite) doesn't interrupt them: songs are cached as they play and fetched ahead while the
 * connection is good, and a song cached whole plays from the cache with no connection at all.
 *
 * Cached bytes are keyed by the exact copy and format, never just the song, so a song that later
 * resolves to another copy (a different service or bitrate) can't mix in the wrong bytes.
 * Streams that can be resumed partway (files, YouTube, Qobuz) are cached as they play ("copy:"
 * keys); Jellyfin's live transcodes can't, so they're only cached by fetching them whole ahead of
 * time ("whole:" keys) and only played from the cache once complete.
 */
@OptIn(UnstableApi::class)
class PlaybackCache(
    context: Context,
    private val index: CachedCopyDao,
    private val maxBytes: () -> Long,
    private val enabled: () -> Boolean,
) {
    private val evictor = AdjustableLruEvictor(maxBytes)
    val cache: SimpleCache by lazy {
        SimpleCache(File(context.filesDir, "playback-cache"), evictor, StandaloneDatabaseProvider(context))
    }

    /** What was played or fetched for each queued song, to index once it's all there. */
    private val pending = ConcurrentHashMap<String, CachedCopyEntity>()

    /** The cache key for a copy, or null when it isn't cached (caching off, or live audio). */
    fun keyFor(copy: Track, stream: SourceStream): String? {
        if (!enabled() || copy.isLive) return null
        val id = hash("${copy.key}|${stream.description}|${stream.kbps}")
        return if (stream.supportsRangeRequests) "$RESUMABLE$id" else "$WHOLE$id"
    }

    /** A Pandora station track: a plain audio file, cached as it plays like any other. */
    fun keyForStationTrack(track: Track): String? = if (enabled()) "$RESUMABLE${hash(track.key)}" else null

    /** Remembers what [trackKey] is being played (or fetched) as, to index it once complete. */
    fun remember(trackKey: String, cacheKey: String, copy: Track, description: String, kbps: Int?, gainDb: Float?) {
        pending[trackKey] = CachedCopyEntity(trackKey, cacheKey, copy, description, kbps, gainDb, System.currentTimeMillis())
    }

    /** Indexes [trackKey] if it has been cached whole, so it can play with no connection. */
    suspend fun recordIfComplete(trackKey: String) {
        val entry = pending[trackKey] ?: return
        if (isComplete(entry.cacheKey)) {
            pending.remove(trackKey)
            index.upsert(entry.copy(cachedAt = System.currentTimeMillis()))
        }
    }

    /** The cached copy of [trackKey] if it's all there (the index is tidied if it isn't). */
    suspend fun completeCopy(trackKey: String): CachedCopyEntity? {
        if (!enabled()) return null
        val entry = index.get(trackKey) ?: return null
        if (isComplete(entry.cacheKey)) return entry
        index.delete(trackKey)
        return null
    }

    fun isComplete(cacheKey: String): Boolean = runCatching {
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        length != C.LENGTH_UNSET.toLong() && length > 0 && cache.isCached(cacheKey, 0, length)
    }.getOrDefault(false)

    /** Drops a half-fetched copy that can't be resumed. */
    fun discard(cacheKey: String) = runCatching { cache.removeResource(cacheKey) }

    val usedBytes: Long get() = runCatching { cache.cacheSpace }.getOrDefault(0)

    suspend fun clear() = withContext(Dispatchers.IO) {
        index.clear()
        pending.clear()
        cache.keys.toList().forEach { runCatching { cache.removeResource(it) } }
    }

    /** Applies a smaller size limit straight away. */
    fun trim() {
        // The cache calls the evictor holding its own lock; take it first here too, in that order.
        synchronized(cache) { evictor.evict(cache, 0) }
    }

    /** Wraps [upstream] so items with a cache key read and write the cache (see the class notes). */
    fun dataSourceFactory(upstream: DataSource.Factory): DataSource.Factory = DataSource.Factory {
        Router(upstream.createDataSource())
    }

    /** A data source that writes everything it reads into the cache, for fetching ahead. */
    fun writer(upstream: DataSource): CacheDataSource = CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private inner class Router(private val upstream: DataSource) : DataSource {
        private var opened: DataSource? = null
        private val listeners = ArrayList<TransferListener>()

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val key = dataSpec.key
            val source = when {
                key == null -> upstream
                key.startsWith(RESUMABLE) -> CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                key.startsWith(WHOLE) && isComplete(key) -> CacheDataSource(cache, upstream, CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                else -> upstream
            }
            opened = source
            return source.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = opened!!.read(buffer, offset, length)

        override fun getUri(): Uri? = opened?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = opened?.responseHeaders ?: emptyMap()

        override fun close() {
            opened?.close()
            opened = null
        }
    }

    companion object {
        private const val TAG = "PlaybackCache"
        const val RESUMABLE = "copy:"
        const val WHOLE = "whole:"

        /** The address given to the player for a song played from the cache (it never reaches the network). */
        fun cachedUri(cacheKey: String): String = "cache://song/$cacheKey"

        private fun hash(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(16).joinToString("") { "%02x".format(it) }
    }
}

/** Least-recently-used eviction with a limit read each time, so a new size applies at once. */
@OptIn(UnstableApi::class)
class AdjustableLruEvictor(private val maxBytes: () -> Long) : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val byTime = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (byTime != 0) byTime else a.compareTo(b)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    /** Call with the cache's lock held (the cache's callbacks are). */
    fun evict(cache: Cache, required: Long) {
        while (size + required > maxBytes() && spans.isNotEmpty()) {
            try {
                cache.removeSpan(spans.first())
            } catch (e: Exception) {
                Log.w("AdjustableLruEvictor", "Couldn't evict", e)
                break
            }
        }
    }
}
