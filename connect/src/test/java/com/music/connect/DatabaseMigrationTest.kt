package com.music.connect

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.music.connect.data.ConnectDb
import com.music.connect.data.Library
import com.music.connect.net.ServerApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** The phone's database, upgraded: what people kept on version 1 is all still there. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DatabaseMigrationTest {
    @get:Rule
    val migrations = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), ConnectDb::class.java)

    @Test
    fun `version 1 playlists, waiting changes and downloads survive the upgrade to shared playlists`() {
        val track = """{"source":"JELLYFIN","sourceId":"song1","title":"Neon Tidepool","artist":"Velvet Satellites"}"""
        migrations.createDatabase(DB, 1).use { db ->
            db.execSQL("INSERT INTO playlists (id, name, revision, updatedAt, artwork, keepOffline) VALUES ('p1', 'Road trip', 3, 10, '[]', 1)")
            db.execSQL("INSERT INTO entries (playlistId, entryId, position, track, addedAt) VALUES ('p1', 'e1', 0, '$track', 5)")
            db.execSQL("INSERT INTO entries (playlistId, entryId, position, track, addedAt) VALUES ('p1', 'local-x', 1, '$track', 6)")
            db.execSQL("INSERT INTO pending (kind, playlistId, payload, createdAt) VALUES ('add', 'p1', '{\"tracks\":[$track],\"localIds\":[\"local-x\"]}', 6)")
            db.execSQL("INSERT INTO downloads (`key`, track, file, bytes, description, state, updatedAt) VALUES ('JELLYFIN:song1', '$track', '/f.flac', 10, 'FLAC', 'done', 7)")
        }
        migrations.runMigrationsAndValidate(DB, 2, true, ConnectDb.MIGRATION_1_2).use { db ->
            db.query("SELECT name, keepOffline, revision, sharing FROM playlists").use {
                it.moveToFirst()
                assertEquals("Road trip", it.getString(0))
                assertEquals(1, it.getInt(1))
                assertEquals(3, it.getInt(2))
                // Not known yet: the next sync fetches the playlist again to fill it in.
                assertTrue(it.isNull(3))
            }
            db.query("SELECT COUNT(*), COUNT(addedBy) FROM entries").use {
                it.moveToFirst()
                assertEquals(2, it.getInt(0))
                assertEquals(0, it.getInt(1))
            }
            db.query("SELECT kind, payload FROM pending").use {
                it.moveToFirst()
                assertEquals("add", it.getString(0))
                assertTrue(it.getString(1).contains("local-x"))
            }
            db.query("SELECT file, state FROM downloads").use {
                it.moveToFirst()
                assertEquals("/f.flac", it.getString(0))
                assertEquals("done", it.getString(1))
            }
        }

        // And the app reads it: the person's own, not shared, still kept offline.
        val context = RuntimeEnvironment.getApplication()
        val room = Room.databaseBuilder(context, ConnectDb::class.java, DB).addMigrations(ConnectDb.MIGRATION_1_2).allowMainThreadQueries().build()
        try {
            val library = Library(room, ServerApi(OkHttpClient(), { "http://127.0.0.1:1" }, { null }))
            runBlocking {
                val playlist = library.all.first().single()
                assertEquals("Road trip", playlist.name)
                assertTrue(playlist.mine && !playlist.shared && playlist.keepOffline)
                assertEquals(listOf("e1", "local-x"), library.entriesNow("p1").map { it.entryId })
                assertNull(library.entriesNow("p1").first().addedBy)
                assertEquals(listOf("p1"), library.offlinePlaylists())
            }
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB = "migration-test.db"
    }
}
