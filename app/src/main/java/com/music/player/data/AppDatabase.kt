package com.music.player.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PlaylistEntity::class, PlaylistEntryEntity::class, DownloadEntity::class, LidarrRequestEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
    abstract fun lidarrRequests(): LidarrRequestDao

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

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "music.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
