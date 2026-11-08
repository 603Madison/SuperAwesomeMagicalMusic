package com.music.connect.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A playlist as last seen on the server, plus the changes made here since. */
@Entity(tableName = "playlists")
data class PlaylistRow(
    @PrimaryKey val id: String,
    val name: String,
    val revision: Long,
    val updatedAt: Long,
    /** Covers, as a JSON list. */
    val artwork: String = "[]",
    /** Keep every song downloaded, for listening offline. */
    val keepOffline: Boolean = false,
)

@Entity(tableName = "entries", primaryKeys = ["playlistId", "entryId"])
data class EntryRow(
    val playlistId: String,
    /** The server's id for the entry, or `local-…` for one added here and not sent yet. */
    val entryId: String,
    val position: Int,
    /** The song, as JSON. */
    val track: String,
    val addedAt: Long,
)

/** A change made here, waiting to go to the server (in order). */
@Entity(tableName = "pending")
data class PendingRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val playlistId: String,
    /** What the change needs, as JSON. */
    val payload: String,
    val createdAt: Long,
)

@Entity(tableName = "downloads")
data class DownloadRow(
    /** The song's key (see ApiTrack.key). */
    @PrimaryKey val key: String,
    val track: String,
    /** The file, once it's all there. */
    val file: String? = null,
    val bytes: Long = 0,
    val description: String = "",
    /** queued, done or failed. */
    val state: String = STATE_QUEUED,
    val error: String? = null,
    val updatedAt: Long,
) {
    companion object {
        const val STATE_QUEUED = "queued"
        const val STATE_DONE = "done"
        const val STATE_FAILED = "failed"
    }
}

/** A listen not sent to the server yet (made offline, say). */
@Entity(tableName = "listens")
data class ListenRow(@PrimaryKey(autoGenerate = true) val id: Long = 0, val listen: String)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    fun all(): Flow<List<PlaylistRow>>

    @Query("SELECT * FROM playlists")
    suspend fun allNow(): List<PlaylistRow>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun one(id: String): Flow<PlaylistRow?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: String): PlaylistRow?

    @Upsert
    suspend fun upsert(row: PlaylistRow)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM entries WHERE playlistId = :id ORDER BY position")
    fun entries(id: String): Flow<List<EntryRow>>

    @Query("SELECT * FROM entries WHERE playlistId = :id ORDER BY position")
    suspend fun entriesNow(id: String): List<EntryRow>

    @Query("SELECT COUNT(*) FROM entries WHERE playlistId = :id")
    suspend fun count(id: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntries(rows: List<EntryRow>)

    @Query("DELETE FROM entries WHERE playlistId = :id")
    suspend fun clearEntries(id: String)

    @Query("DELETE FROM entries WHERE playlistId = :id AND entryId IN (:entryIds)")
    suspend fun deleteEntries(id: String, entryIds: List<String>)

    @Transaction
    suspend fun replace(row: PlaylistRow, entries: List<EntryRow>) {
        upsert(row)
        clearEntries(row.id)
        insertEntries(entries)
    }

    @Query("SELECT playlistId, COUNT(*) AS count FROM entries GROUP BY playlistId")
    fun counts(): Flow<List<PlaylistCount>>
}

data class PlaylistCount(val playlistId: String, val count: Int)

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending ORDER BY id")
    suspend fun all(): List<PendingRow>

    @Query("SELECT COUNT(*) FROM pending")
    fun count(): Flow<Int>

    @Insert
    suspend fun add(row: PendingRow)

    @Upsert
    suspend fun update(row: PendingRow)

    @Query("DELETE FROM pending WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM pending")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY updatedAt DESC")
    fun all(): Flow<List<DownloadRow>>

    @Query("SELECT * FROM downloads WHERE `key` = :key")
    suspend fun get(key: String): DownloadRow?

    @Query("SELECT * FROM downloads WHERE state = 'queued' ORDER BY updatedAt")
    suspend fun queued(): List<DownloadRow>

    @Query("SELECT * FROM downloads WHERE state = 'done'")
    suspend fun done(): List<DownloadRow>

    @Upsert
    suspend fun upsert(row: DownloadRow)

    @Query("DELETE FROM downloads WHERE `key` = :key")
    suspend fun delete(key: String)
}

@Dao
interface ListenDao {
    @Query("SELECT * FROM listens ORDER BY id LIMIT 500")
    suspend fun batch(): List<ListenRow>

    @Insert
    suspend fun add(row: ListenRow)

    @Query("DELETE FROM listens WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}

@Database(entities = [PlaylistRow::class, EntryRow::class, PendingRow::class, DownloadRow::class, ListenRow::class], version = 1)
abstract class ConnectDb : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun pending(): PendingDao
    abstract fun downloads(): DownloadDao
    abstract fun listens(): ListenDao

    companion object {
        fun build(context: Context): ConnectDb = Room.databaseBuilder(context, ConnectDb::class.java, "connect.db").build()
    }
}
