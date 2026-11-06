package com.music.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        PlaylistEntity::class, PlaylistEntryEntity::class, DownloadEntity::class, LidarrRequestEntity::class,
        HistoryEntity::class, PendingScrobbleEntity::class, LidarrAddedEntity::class, CachedCopyEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun lidarrRequests(): LidarrRequestDao
    abstract fun history(): HistoryDao
    abstract fun scrobbles(): ScrobbleDao
    abstract fun lidarrAdded(): LidarrAddedDao
    abstract fun cachedCopies(): CachedCopyDao

    companion object {
        /** 1.1.0: playlists gain an owner; Lidarr requests are queued. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN ownerId TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lidarr_requests` (`artistKey` TEXT NOT NULL, " +
                        "`artistName` TEXT NOT NULL, `trackTitle` TEXT, `requestedAt` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, `lastAttemptAt` INTEGER, `lastError` TEXT, " +
                        "PRIMARY KEY(`artistKey`))",
                )
            }
        }

        /** 1.2.0: playlists can be kept downloaded; downloads remember their quality. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE playlists ADD COLUMN keepDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN kbps INTEGER")
                db.execSQL("ALTER TABLE downloads ADD COLUMN autoDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE downloads ADD COLUMN qualityCheckedAt INTEGER")
            }
        }

        /** 1.9.0: listening history, scrobbles waiting to be sent, Lidarr artists awaiting downloads, cached songs. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val track = { prefix: String ->
                    "`${prefix}_source` TEXT NOT NULL, `${prefix}_sourceId` TEXT NOT NULL, `${prefix}_title` TEXT NOT NULL, " +
                        "`${prefix}_artist` TEXT NOT NULL, `${prefix}_album` TEXT, `${prefix}_durationMs` INTEGER, " +
                        "`${prefix}_artworkUrl` TEXT, `${prefix}_isrc` TEXT"
                }
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `history` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playedAt` INTEGER NOT NULL, " +
                        "`heardOn` TEXT, `heard` INTEGER NOT NULL, ${track("track")})",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_history_playedAt` ON `history` (`playedAt`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_scrobbles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`playedAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `listenBrainzDone` INTEGER NOT NULL, " +
                        "`lastFmDone` INTEGER NOT NULL, ${track("track")})",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `lidarr_added` (`artistId` INTEGER NOT NULL, `artistName` TEXT NOT NULL, " +
                        "`addedAt` INTEGER NOT NULL, PRIMARY KEY(`artistId`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `cached_copies` (`trackKey` TEXT NOT NULL, `cacheKey` TEXT NOT NULL, " +
                        "`description` TEXT NOT NULL, `kbps` INTEGER, `gainDb` REAL, `cachedAt` INTEGER NOT NULL, " +
                        "${track("copy")}, PRIMARY KEY(`trackKey`))",
                )
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "music.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}
