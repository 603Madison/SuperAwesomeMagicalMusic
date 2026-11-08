package com.music.connect.playback

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.connect.Container
import com.music.connect.net.ApiTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What Android Auto (and other media browsers) can browse: playlists (which work offline), your
 * downloads, and radio (SiriusXM channels and radio stations, online). Choosing a song in a list
 * plays the whole list from it. Search goes to the server, or to your downloads offline.
 */
@OptIn(UnstableApi::class)
class LibraryCallback(
    private val c: Container,
    private val queueStore: QueueStore,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    private val searches = ConcurrentHashMap<String, List<ApiTrack>>()

    private val server: String get() = c.settings.value.server
    private fun art(url: String?) = MediaItems.artworkUri(server, url, 256)

    // Android Auto's older connection waits for this on the main thread: answer at once.
    override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "SuperAwesomeMagicalMusic"), params))

    override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String): ListenableFuture<LibraryResult<MediaItem>> =
        scope.future {
            val track = MediaItems.track(mediaId)
            if (track != null) LibraryResult.ofItem(MediaItems.playable(track, server, ::art), null)
            else LibraryResult.ofItem(folder(mediaId, mediaId), null)
        }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val items: List<MediaItem> = when {
            parentId == ROOT -> listOf(
                folder(PLAYLISTS, "Playlists"),
                folder(DOWNLOADS, "Downloads"),
                folder(RADIO, "Radio"),
            )
            parentId == PLAYLISTS -> c.library.all.first().map { folder("$PLAYLIST${it.id}", it.name, "${it.count} songs") }
            parentId.startsWith(PLAYLIST) -> {
                val id = parentId.removePrefix(PLAYLIST)
                c.library.entriesNow(id).mapIndexed { i, e -> browseItem("$IN_PLAYLIST$id:$i", e.track) }
            }
            parentId == DOWNLOADS -> c.downloads.all.first().filter { it.done }.mapIndexed { i, d -> browseItem("$IN_DOWNLOADS$i", d.track) }
            parentId == RADIO -> radioFolders()
            parentId.startsWith(SXM) -> online { c.api.channels(parentId.removePrefix(SXM)).filter { !it.locked }.map { browseItem(MediaItems.id(it.track), it.track) } }
            parentId.startsWith(LOCAL_RADIO) -> online {
                val country = java.util.Locale.getDefault().country.ifBlank { null }
                c.api.radioSearch(parentId.removePrefix(LOCAL_RADIO), "", country).map { browseItem(MediaItems.id(it), it) }
            }
            else -> emptyList()
        }
        val from = (page * pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
    }

    private suspend fun radioFolders(): List<MediaItem> = online {
        c.api.sources().filter { it.enabled && !it.hidden }.mapNotNull { source ->
            when (source.kind) {
                "SIRIUSXM" -> folder("$SXM${source.id}", source.name, "Channels")
                "RADIO" -> folder("$LOCAL_RADIO${source.id}", "Popular radio", "Stations near you")
                else -> null
            }
        }
    }

    /** Asks the server when there's a connection, else shows nothing (rather than waiting). */
    private suspend fun online(block: suspend () -> List<MediaItem>): List<MediaItem> =
        if (!c.network.state.value.isOnline) emptyList() else withTimeoutOrNull(20_000) { runCatching { block() }.getOrNull() } ?: emptyList()

    override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?): ListenableFuture<LibraryResult<Void>> =
        scope.future(Dispatchers.IO) {
            val results = search(query)
            searches[query] = results
            session.notifySearchResultChanged(browser, query, results.size, params)
            LibraryResult.ofVoid()
        }

    override fun onGetSearchResult(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future(Dispatchers.IO) {
        val results = searches[query] ?: search(query)
        val items = results.map { browseItem(MediaItems.id(it), it) }
        val from = (page * pageSize).coerceAtMost(items.size)
        LibraryResult.ofItemList(ImmutableList.copyOf(items.subList(from, (from + pageSize).coerceAtMost(items.size))), params)
    }

    /** The server's search when online (library first), else the downloads that match. */
    private suspend fun search(query: String): List<ApiTrack> {
        if (c.network.state.value.isOnline) {
            val found = runCatching { c.api.search(query) }.getOrNull()
            if (found != null) return found.groups.sortedByDescending { it.kind == "JELLYFIN" }.flatMap { it.tracks }.take(50)
        }
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        return c.downloads.all.first().filter { it.done }.map { it.track }
            .filter { t -> words.all { w -> "${t.title} ${t.artist} ${t.album.orEmpty()}".lowercase().contains(w) } }
    }

    override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>): ListenableFuture<MutableList<MediaItem>> =
        scope.future(Dispatchers.IO) { mediaItems.flatMap { expand(it).first }.toMutableList() }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = scope.future(Dispatchers.IO) {
        // A song chosen in a list plays the whole list from there.
        if (mediaItems.size == 1) {
            val (items, start) = expand(mediaItems[0])
            return@future MediaSession.MediaItemsWithStartPosition(items, start, startPositionMs)
        }
        MediaSession.MediaItemsWithStartPosition(mediaItems.flatMap { expand(it).first }, startIndex, startPositionMs)
    }

    override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo): ListenableFuture<MediaSession.MediaItemsWithStartPosition> =
        scope.future(Dispatchers.IO) {
            val saved = queueStore.load() ?: return@future MediaSession.MediaItemsWithStartPosition(emptyList(), 0, 0)
            val items = saved.ids.mapNotNull { MediaItems.track(it) }.map { MediaItems.playable(it, server, ::art) }
            MediaSession.MediaItemsWithStartPosition(items, saved.index.coerceIn(0, (items.size - 1).coerceAtLeast(0)), saved.positionMs)
        }

    /** The playable items an item stands for (a voice search, a song in a list…), and which to start with. */
    private suspend fun expand(item: MediaItem): Pair<List<MediaItem>, Int> {
        val query = item.requestMetadata.searchQuery
        if (item.mediaId.isEmpty() && query != null) {
            // "Play … on SuperAwesomeMagicalMusic": the best matches (or the downloads, for "play music").
            val found = if (query.isBlank()) c.downloads.all.first().filter { it.done }.map { it.track }.shuffled() else search(query)
            return found.map { MediaItems.playable(it, server, ::art) } to 0
        }
        return expand(item.mediaId)
    }

    private suspend fun expand(mediaId: String): Pair<List<MediaItem>, Int> {
        MediaItems.track(mediaId)?.let { return listOf(MediaItems.playable(it, server, ::art)) to 0 }
        if (mediaId.startsWith(IN_PLAYLIST)) {
            val (id, index) = mediaId.removePrefix(IN_PLAYLIST).let { it.substringBeforeLast(':') to (it.substringAfterLast(':').toIntOrNull() ?: 0) }
            return c.library.entriesNow(id).map { MediaItems.playable(it.track, server, ::art) } to index
        }
        if (mediaId.startsWith(IN_DOWNLOADS)) {
            val index = mediaId.removePrefix(IN_DOWNLOADS).toIntOrNull() ?: 0
            return c.downloads.all.first().filter { it.done }.map { MediaItems.playable(it.track, server, ::art) } to index
        }
        return emptyList<MediaItem>() to 0
    }

    private fun browseItem(id: String, track: ApiTrack): MediaItem =
        MediaItem.Builder().setMediaId(id).setMediaMetadata(MediaItems.metadata(track, ::art)).build()

    private fun folder(id: String, title: String, subtitle: String? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .setExtras(Bundle())
                .build(),
        )
        .build()

    companion object {
        const val ROOT = "root"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST = "playlist:"
        const val IN_PLAYLIST = "inpl:"
        const val DOWNLOADS = "downloads"
        const val IN_DOWNLOADS = "indl:"
        const val RADIO = "radio"
        private const val SXM = "sxm:"
        private const val LOCAL_RADIO = "localradio:"
    }
}
