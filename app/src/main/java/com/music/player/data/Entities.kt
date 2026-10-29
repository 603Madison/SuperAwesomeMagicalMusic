package com.music.player.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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
)
