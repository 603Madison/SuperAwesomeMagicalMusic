package com.music.player.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {
    /** Fills in artwork and album for a song saved without them, in every playlist it's in. */
    @Query(
        "UPDATE playlist_entries SET track_artworkUrl = COALESCE(track_artworkUrl, :artworkUrl), " +
            "track_album = COALESCE(track_album, :album) WHERE track_source = :source AND track_sourceId = :sourceId",
    )
    suspend fun fillDetails(source: String, sourceId: String, artworkUrl: String?, album: String?)

    @Query(
        """
        SELECT p.id, p.name, p.description, p.updatedAt,
               (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount,
               (p.syncedUpdatedAt IS NULL OR p.updatedAt > p.syncedUpdatedAt) AS needsUpload,
               p.keepDownloaded
        FROM playlists p WHERE p.deleted = 0 AND p.ownerId IS :owner ORDER BY p.name COLLATE NOCASE
        """,
    )
    fun observeSummaries(owner: String?): Flow<List<PlaylistSummary>>

    /** Every entry's artwork, in playlist order, for building playlist covers. */
    @Query("SELECT playlistId, track_artworkUrl AS artworkUrl FROM playlist_entries WHERE track_artworkUrl IS NOT NULL ORDER BY playlistId, position")
    fun observeArtwork(): Flow<List<PlaylistArtwork>>

    @Query("SELECT * FROM playlists WHERE deleted = 0 AND ownerId IS :owner ORDER BY name COLLATE NOCASE")
    suspend fun active(owner: String?): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE deleted = 0 AND ownerId IS :owner AND name = :name LIMIT 1")
    suspend fun findByName(owner: String?, name: String): PlaylistEntity?

    /** Gives playlists made before anyone signed in to [owner]. */
    @Query("UPDATE playlists SET ownerId = :owner WHERE ownerId IS NULL")
    suspend fun adoptUnowned(owner: String)

    @Query("UPDATE playlists SET keepDownloaded = :keep WHERE id = :id")
    suspend fun setKeepDownloaded(id: String, keep: Boolean)

    /** Songs in playlists marked "keep downloaded". */
    @Query(
        """
        SELECT e.* FROM playlist_entries e JOIN playlists p ON p.id = e.playlistId
        WHERE p.keepDownloaded = 1 AND p.deleted = 0
        """,
    )
    suspend fun keptEntries(): List<PlaylistEntryEntity>

    @Query("UPDATE playlists SET ownerId = :to WHERE ownerId = :from")
    suspend fun reassign(from: String, to: String?)

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    fun observeEntries(id: String): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE ownerId IS :owner")
    suspend fun all(owner: String?): List<PlaylistEntity>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    suspend fun entries(id: String): List<PlaylistEntryEntity>

    @Upsert
    suspend fun upsert(playlist: PlaylistEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :id")
    suspend fun clearEntries(id: String)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun purge(id: String)

    /** Forget everything known about the Nextcloud copies, e.g. after switching server or folder. */
    @Query("UPDATE playlists SET remoteFileName = NULL, remoteEtag = NULL, syncedUpdatedAt = NULL WHERE ownerId IS :owner")
    suspend fun resetSyncState(owner: String?)

    /**
     * Records which Nextcloud file holds a playlist, touching nothing else: an edit made while a
     * sync runs (a rename, say) must not be overwritten by the sync's older copy of the row.
     */
    @Query("UPDATE playlists SET remoteFileName = :fileName, remoteEtag = :etag WHERE id = :id")
    suspend fun setRemoteFile(id: String, fileName: String, etag: String?)

    /** As [setRemoteFile], after uploading the playlist as it was at [syncedUpdatedAt]. */
    @Query("UPDATE playlists SET remoteFileName = :fileName, remoteEtag = :etag, syncedUpdatedAt = :syncedUpdatedAt WHERE id = :id")
    suspend fun markUploaded(id: String, fileName: String, etag: String?, syncedUpdatedAt: Long)

    @Query("DELETE FROM playlists WHERE deleted = 1 AND ownerId IS :owner")
    suspend fun purgeTombstones(owner: String?)

    @Transaction
    suspend fun replace(playlist: PlaylistEntity, entries: List<PlaylistEntryEntity>) {
        upsert(playlist)
        clearEntries(playlist.id)
        insertEntries(entries)
    }
}

@Dao
interface LidarrRequestDao {
    @Query("SELECT * FROM lidarr_requests ORDER BY requestedAt")
    fun observeAll(): Flow<List<LidarrRequestEntity>>

    @Query("SELECT * FROM lidarr_requests ORDER BY requestedAt")
    suspend fun all(): List<LidarrRequestEntity>

    /** Queues an artist unless it is already waiting. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun enqueue(request: LidarrRequestEntity)

    @Query("UPDATE lidarr_requests SET attempts = attempts + 1, lastAttemptAt = :at, lastError = :error WHERE artistKey = :key")
    suspend fun recordFailure(key: String, error: String, at: Long)

    @Query("DELETE FROM lidarr_requests WHERE artistKey = :key")
    suspend fun delete(key: String)
}

@Dao
interface DownloadDao {
    @Query(
        "UPDATE downloads SET track_artworkUrl = COALESCE(track_artworkUrl, :artworkUrl), " +
            "track_album = COALESCE(track_album, :album) WHERE trackKey = :key",
    )
    suspend fun fillDetails(key: String, artworkUrl: String?, album: String?)

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackKey = :key")
    suspend fun get(key: String): DownloadEntity?

    @Query("SELECT * FROM downloads")
    suspend fun all(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE trackKey = :key AND status = 'COMPLETED'")
    suspend fun completed(key: String): DownloadEntity?

    @Query(
        """
        SELECT * FROM downloads WHERE status = 'COMPLETED'
        AND (track_title LIKE '%' || :query || '%' OR track_artist LIKE '%' || :query || '%'
             OR track_album LIKE '%' || :query || '%')
        ORDER BY track_artist, track_album, track_title
        """,
    )
    suspend fun searchCompleted(query: String): List<DownloadEntity>

    @Query("SELECT trackKey FROM downloads WHERE status = 'COMPLETED'")
    fun observeCompletedKeys(): Flow<List<String>>

    @Upsert
    suspend fun upsert(download: DownloadEntity)

    @Query("UPDATE downloads SET status = :status, progress = :progress WHERE trackKey = :key")
    suspend fun updateProgress(key: String, status: DownloadStatus, progress: Float)

    @Query("DELETE FROM downloads WHERE trackKey = :key")
    suspend fun delete(key: String)
}

/** One playlist entry's artwork (see PlaylistDao.observeArtwork). */
data class PlaylistArtwork(val playlistId: String, val artworkUrl: String)
