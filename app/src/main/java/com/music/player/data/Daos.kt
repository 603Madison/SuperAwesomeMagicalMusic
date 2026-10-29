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
    @Query(
        """
        SELECT p.id, p.name, p.description, p.updatedAt,
               (SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount,
               (p.syncedUpdatedAt IS NULL OR p.updatedAt > p.syncedUpdatedAt) AS needsUpload
        FROM playlists p WHERE p.deleted = 0 ORDER BY p.name COLLATE NOCASE
        """,
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: String): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :id ORDER BY position")
    fun observeEntries(id: String): Flow<List<PlaylistEntryEntity>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun all(): List<PlaylistEntity>

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
    @Query("UPDATE playlists SET remoteFileName = NULL, remoteEtag = NULL, syncedUpdatedAt = NULL")
    suspend fun resetSyncState()

    @Query("DELETE FROM playlists WHERE deleted = 1")
    suspend fun purgeTombstones()

    @Transaction
    suspend fun replace(playlist: PlaylistEntity, entries: List<PlaylistEntryEntity>) {
        upsert(playlist)
        clearEntries(playlist.id)
        insertEntries(entries)
    }
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE trackKey = :key")
    suspend fun get(key: String): DownloadEntity?

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
