package com.music.player.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.music.player.model.SourceType
import com.music.player.model.Track

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    /** Tombstone: kept until the deletion has been pushed to Nextcloud. */
    val deleted: Boolean = false,
    /** File name of this playlist in the Nextcloud folder, once uploaded. */
    val remoteFileName: String? = null,
    val remoteEtag: String? = null,
    /** The [updatedAt] value that was last uploaded to or downloaded from Nextcloud. */
    val syncedUpdatedAt: Long? = null,
    /** The Nextcloud account (`user@server`) the playlist belongs to; null when not connected. */
    val ownerId: String? = null,
    /** Keep every song downloaded, including songs added later, upgrading to better copies. */
    @ColumnInfo(defaultValue = "0") val keepDownloaded: Boolean = false,
) {
    val needsUpload: Boolean get() = syncedUpdatedAt == null || updatedAt > syncedUpdatedAt
}

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistEntryEntity(
    val playlistId: String,
    val position: Int,
    @Embedded(prefix = "track_") val track: Track,
)

data class PlaylistSummary(
    val id: String,
    val name: String,
    val description: String?,
    val updatedAt: Long,
    val trackCount: Int,
    val needsUpload: Boolean,
    val keepDownloaded: Boolean,
)

/** An artist waiting to be checked in (and if needed added to) Lidarr once it is reachable. */
@Entity(tableName = "lidarr_requests")
data class LidarrRequestEntity(
    /** Normalized artist name, so the same artist is only queued once. */
    @PrimaryKey val artistKey: String,
    val artistName: String,
    /** The song that triggered the request, for display. */
    val trackTitle: String? = null,
    val requestedAt: Long,
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val lastError: String? = null,
)

enum class DownloadStatus { QUEUED, DOWNLOADING, COMPLETED, FAILED }

@Entity(tableName = "downloads")
data class DownloadEntity(
    @PrimaryKey val trackKey: String,
    @Embedded(prefix = "track_") val track: Track,
    val status: DownloadStatus,
    val progress: Float = 0f,
    val filePath: String? = null,
    val sizeBytes: Long = 0,
    /** Where the file actually came from, e.g. "Jellyfin · FLAC (original file)". */
    val qualityDescription: String? = null,
    val error: String? = null,
    val createdAt: Long,
    /** Bitrate of the downloaded copy, to recognise better copies later. */
    val kbps: Int? = null,
    /** Downloaded because a kept playlist contains the song (removed when no longer needed). */
    @ColumnInfo(defaultValue = "0") val autoDownloaded: Boolean = false,
    /** When the services were last checked for a better copy. */
    val qualityCheckedAt: Long? = null,
) {
    val isLossless: Boolean get() = filePath?.substringAfterLast('.')?.lowercase() in setOf("flac", "wav", "alac")

    /** The service the file came from, as recorded in [qualityDescription]. */
    val downloadedFrom: SourceType?
        get() = SourceType.entries.firstOrNull { qualityDescription?.startsWith("${it.label} ·") == true }

    /** The known bitrate, or an estimate from file size and duration for older downloads. */
    val effectiveKbps: Int?
        get() = kbps ?: track.durationMs?.takeIf { it > 0 && sizeBytes > 0 }?.let { (sizeBytes * 8 / it).toInt() }
}
