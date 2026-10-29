package com.music.player.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.music.player.model.SourceType
import com.music.player.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun `version 1 playlists survive the upgrade and have no owner`() {
        migrations.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, createdAt, updatedAt, deleted) VALUES ('p1', 'Road Trip', 1, 2, 0)")
        }
        migrations.runMigrationsAndValidate(DB, 2, true, AppDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT name, ownerId FROM playlists").use { cursor ->
                cursor.moveToFirst()
                assertEquals("Road Trip", cursor.getString(0))
                assertEquals(true, cursor.isNull(1))
            }
            db.query("SELECT COUNT(*) FROM lidarr_requests").use { cursor ->
                cursor.moveToFirst()
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun `each user only sees their own playlists, and signing in adopts unowned ones`() = runTest {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao = db.playlists()
        val track = Track(SourceType.JELLYFIN, "1", "Song", "Artist")
        dao.replace(PlaylistEntity("local", "Before sign-in", createdAt = 0, updatedAt = 0), listOf(PlaylistEntryEntity("local", 0, track)))
        dao.upsert(PlaylistEntity("other", "Someone else's", createdAt = 0, updatedAt = 0, ownerId = "user-b"))

        assertEquals(listOf("Before sign-in"), dao.observeSummaries(null).first().map { it.name })
        dao.adoptUnowned("user-a")
        assertEquals(listOf("Before sign-in"), dao.observeSummaries("user-a").first().map { it.name })
        assertEquals(1, dao.observeSummaries("user-a").first().single().trackCount)
        assertEquals(emptyList<String>(), dao.observeSummaries(null).first().map { it.name })
        assertEquals(listOf("Someone else's"), dao.active("user-b").map { it.name })
        db.close()
    }

    private companion object {
        const val DB = "migration-test"
    }
}
