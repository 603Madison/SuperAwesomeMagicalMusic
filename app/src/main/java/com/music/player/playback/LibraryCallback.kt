package com.music.player.playback

import android.os.Bundle
import android.util.Base64
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.music.player.AppContainer
import com.music.player.data.DownloadStatus
import com.music.player.model.Track
import com.music.player.net.AppJson
import com.music.player.sources.TrackMatcher
import com.music.player.sources.siriusxm.SxmChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.future
import java.util.concurrent.ConcurrentHashMap

/** Browse ids used by Android Auto and other media browsers. */
object LibraryIds {
    const val ROOT = "root"
    const val PLAYLISTS = "playlists"
    const val DOWNLOADS = "downloads"
    const val SIRIUSXM = "siriusxm"
    private const val PLAYLIST = "playlist:"
    private const val CATEGORY = "sxmcat:"
    private const val DOWNLOAD = "download:"
    private const val TRACK = "track:"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun playlist(id: String) = "$PLAYLIST$id"
    fun playlistEntry(id: String, index: Int) = "$PLAYLIST$id:$index"
    fun category(name: String) = "$CATEGORY$name"
    fun download(key: String) = "$DOWNLOAD$key"
    fun track(track: Track) =
        TRACK + Base64.encodeToString(AppJson.encodeToString(Track.serializer(), track).toByteArray(), FLAGS)

    /** (playlist id, entry index or null for the whole playlist) */
    fun parsePlaylist(id: String): Pair<String, Int?>? {
        if (!id.startsWith(PLAYLIST)) return null
        val rest = id.removePrefix(PLAYLIST)
        val index = rest.substringAfterLast(':', "").toIntOrNull()
        return if (index != null) rest.substringBeforeLast(':') to index else rest to null
    }

    fun parseCategory(id: String) = id.takeIf { it.startsWith(CATEGORY) }?.removePrefix(CATEGORY)
    fun parseDownload(id: String) = id.takeIf { it.startsWith(DOWNLOAD) }?.removePrefix(DOWNLOAD)
    fun parseTrack(id: String): Track? = id.takeIf { it.startsWith(TRACK) }?.let {
        runCatching {
            AppJson.decodeFromString(Track.serializer(), String(Base64.decode(it.removePrefix(TRACK), FLAGS)))
        }.getOrNull()
    }
}

/**
 * The media library Android Auto browses: playlists, downloads and SiriusXM channels, plus
 * search, voice requests ("play … on Music") and a "Save song" button for what's playing.
 */
@OptIn(UnstableApi::class)
class LibraryCallback(
    private val container: AppContainer,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    private val searches = ConcurrentHashMap<String, Deferred<List<Track>>>()

    override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
        val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon().add(SAVE_SONG).build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailableSessionCommands(commands).build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> = scope.future {
        if (customCommand.customAction != SAVE_SONG.customAction) return@future SessionResult(SessionError.ERROR_NOT_SUPPORTED)
        val saved = saveCurrentSong(session)
        SessionResult(if (saved) SessionResult.RESULT_SUCCESS else SessionError.ERROR_BAD_VALUE)
    }

    /** Saves the song on SiriusXM (or the current track) to a playlist. */
    private suspend fun saveCurrentSong(session: MediaSession): Boolean {
        val current = session.player.currentMediaItem?.toTrack() ?: return false
        return try {
            if (current.isSiriusXmChannel) {
                val cut = container.siriusXm.nowPlaying.value?.cut?.takeIf { it.isSong } ?: return false
                container.heardSongs.saveToHeardPlaylist(cut)
            } else {
                container.playlists.addTracks(container.playlists.getOrCreate(SAVED_PLAYLIST), listOf(current))
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't save the current song", e)
            false
        }
    }

    /**
     * Answered at once, never through [scope]: Android Auto connects through the legacy
     * MediaBrowserService API, and Media3 blocks the main thread waiting for this result. A
     * future completed by a coroutine on the main thread would never finish, and Android Auto
     * would spin forever.
     */
    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> =
        Futures.immediateFuture(LibraryResult.ofItem(folder(LibraryIds.ROOT, "Music", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED), params))

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> = scope.future {
        val track = LibraryIds.parseTrack(mediaId)
        if (track != null) LibraryResult.ofItem(browsable(track, mediaId), null)
        else LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val children = try {
            children(parentId)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list $parentId", e)
            return@future LibraryResult.ofError(SessionError.ERROR_IO)
        }
        val from = (page * pageSize).coerceAtMost(children.size)
        val to = (from + pageSize).coerceAtMost(children.size)
        LibraryResult.ofItemList(children.subList(from, to), params)
    }

    internal suspend fun children(parentId: String): List<MediaItem> {
        when (parentId) {
            LibraryIds.ROOT -> return buildList {
                add(folder(LibraryIds.PLAYLISTS, "Playlists", MediaMetadata.MEDIA_TYPE_FOLDER_PLAYLISTS))
                add(folder(LibraryIds.DOWNLOADS, "Downloads", MediaMetadata.MEDIA_TYPE_FOLDER_MIXED))
                if (container.siriusXm.isAvailable()) {
                    add(folder(LibraryIds.SIRIUSXM, "SiriusXM", MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS))
                }
            }
            LibraryIds.PLAYLISTS -> return container.playlists.active().map { playlist ->
                folder(LibraryIds.playlist(playlist.id), playlist.name, MediaMetadata.MEDIA_TYPE_PLAYLIST, playable = true)
            }
            LibraryIds.DOWNLOADS -> return completedDownloads().map { browsable(it, LibraryIds.download(it.key)) }
            LibraryIds.SIRIUSXM -> {
                val channels = container.siriusXm.channels().filter { !it.locked }
                val bookmarks = container.settings.current().siriusXmBookmarks
                // Bookmarked channels come first, ready to play; then every category.
                val bookmarked = channels.filter { it.id in bookmarks }.map { channel ->
                    channel.toTrack().let { browsable(it, LibraryIds.track(it)) }
                }
                return bookmarked + channels.map { it.category.ifBlank { "Other" } }
                    .distinct()
                    .sorted()
                    .map { folder(LibraryIds.category(it), it, MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS) }
            }
        }
        LibraryIds.parseCategory(parentId)?.let { category ->
            return container.siriusXm.channels()
                .filter { !it.locked && it.category.ifBlank { "Other" } == category }
                .map { channel -> channel.toTrack().let { browsable(it, LibraryIds.track(it)) } }
        }
        LibraryIds.parsePlaylist(parentId)?.let { (id, _) ->
            return container.playlists.entries(id).mapIndexed { index, track -> browsable(track, LibraryIds.playlistEntry(id, index)) }
        }
        return emptyList()
    }

    override fun onSearch(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        query: String,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<Void>> = scope.future {
        val results = search(query).await()
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
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = scope.future {
        val results = search(query).await().map { browsable(it, LibraryIds.track(it)) }
        val from = (page * pageSize).coerceAtMost(results.size)
        LibraryResult.ofItemList(results.subList(from, (from + pageSize).coerceAtMost(results.size)), params)
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
        val single = mediaItems.singleOrNull()
        if (single != null) {
            expand(single.mediaId)?.let { (tracks, index) ->
                return@future MediaItemsWithStartPosition(tracks.map(Track::toMediaItem), index, C.TIME_UNSET)
            }
            val query = single.requestMetadata.searchQuery
            if (query != null && single.localConfiguration == null && single.toTrack() == null) {
                // A voice request such as "play Get Lucky on Music".
                val tracks = voiceSearch(query)
                return@future MediaItemsWithStartPosition(tracks.map(Track::toMediaItem), 0, C.TIME_UNSET)
            }
        }
        MediaItemsWithStartPosition(resolve(mediaItems), startIndex, startPositionMs)
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> = scope.future { resolve(mediaItems).toMutableList() }

    private suspend fun resolve(items: List<MediaItem>): List<MediaItem> = items.flatMap { item ->
        item.withPlaybackUri()?.let { return@flatMap listOf(it) }
        expand(item.mediaId)?.first?.map(Track::toMediaItem).orEmpty()
    }

    /** Tracks for a browse id, and which of them to start with. */
    internal suspend fun expand(mediaId: String): Pair<List<Track>, Int>? {
        LibraryIds.parseTrack(mediaId)?.let { return listOf(it) to 0 }
        LibraryIds.parsePlaylist(mediaId)?.let { (id, index) ->
            val tracks = container.playlists.entries(id)
            return tracks to (index ?: 0).coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
        }
        LibraryIds.parseDownload(mediaId)?.let { key ->
            val tracks = completedDownloads()
            return tracks to tracks.indexOfFirst { it.key == key }.coerceAtLeast(0)
        }
        return null
    }

    private suspend fun completedDownloads(): List<Track> =
        container.downloads.downloads.first().filter { it.status == DownloadStatus.COMPLETED }.map { it.track }

    private fun search(query: String): Deferred<List<Track>> = searches.getOrPut(query.trim().lowercase()) {
        scope.async {
            val channels = siriusXmChannels(query)
            val library = runCatching { container.jellyfin.search(query, 20) }.getOrDefault(emptyList())
            val streaming = if (library.size >= 5) emptyList() else firstStreamingResults(query)
            channels + library + streaming
        }
    }.also { if (searches.size > 20) searches.clear() }

    /** Voice requests: a SiriusXM channel by name, else songs (Jellyfin first); empty plays downloads. */
    private suspend fun voiceSearch(query: String): List<Track> {
        if (query.isBlank()) return completedDownloads().shuffled().ifEmpty { container.playlists.active().firstOrNull()?.let { container.playlists.entries(it.id) }.orEmpty() }
        siriusXmChannels(query).firstOrNull()?.let { return listOf(it) }
        val library = runCatching { container.jellyfin.search(query, 25) }.getOrDefault(emptyList())
        return library.ifEmpty { firstStreamingResults(query) }
    }

    private suspend fun firstStreamingResults(query: String): List<Track> {
        for (type in container.catalog.fallbackOrder()) {
            val source = container.catalog.source(type)
            if (!source.isAvailable()) continue
            val results = runCatching { source.search(query, 20) }.getOrDefault(emptyList())
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private suspend fun siriusXmChannels(query: String): List<Track> {
        if (!container.siriusXm.isAvailable()) return emptyList()
        val wanted = TrackMatcher.normalize(query).removePrefix("siriusxm ").removePrefix("sirius ")
        if (wanted.isBlank()) return emptyList()
        val channels = runCatching { container.siriusXm.channels() }.getOrDefault(emptyList()).filter { !it.locked }
        return channels.filter { channel ->
            val name = TrackMatcher.normalize(channel.name)
            name == wanted || wanted == "channel ${channel.number}" || (wanted.length >= 4 && name.contains(wanted))
        }.map(SxmChannel::toTrack)
    }

    private fun folder(id: String, title: String, type: Int, playable: Boolean = false): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsBrowsable(true)
                    .setIsPlayable(playable)
                    .setMediaType(type)
                    .build(),
            )
            .build()

    private fun browsable(track: Track, mediaId: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setArtworkUri(track.artworkUrl?.toUri())
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(
                        if (track.isSiriusXmChannel) MediaMetadata.MEDIA_TYPE_RADIO_STATION
                        else MediaMetadata.MEDIA_TYPE_MUSIC,
                    )
                    .build(),
            )
            .build()

    companion object {
        private const val TAG = "LibraryCallback"
        const val SAVED_PLAYLIST = "Saved songs"
        val SAVE_SONG = SessionCommand("com.music.player.SAVE_SONG", Bundle.EMPTY)

        /** The "Save song" button shown in Android Auto and the media notification. */
        val SAVE_SONG_BUTTON: CommandButton = CommandButton.Builder(CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName("Save song")
            .setSessionCommand(SAVE_SONG)
            .build()
    }
}
