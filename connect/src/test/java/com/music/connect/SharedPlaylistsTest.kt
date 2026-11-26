package com.music.connect

import androidx.room.Room
import com.music.connect.data.ConnectDb
import com.music.connect.data.Library
import com.music.connect.net.PlaylistPerson
import com.music.connect.net.ServerApi
import com.music.connect.ui.peopleLine
import com.music.connect.ui.sharedNote
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Shared playlists, with two people's phones against the real server (in-process): Maddie shares
 * a playlist with Sam, Sam's phone sees it as hers, adds a song (offline, then synced) and leaves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SharedPlaylistsTest {
    private lateinit var test: TestServer
    private val http = OkHttpClient()
    private val phones = mutableListOf<ConnectDb>()

    /** One person's phone: their sign-in, their own database, and a switch to take it offline. */
    private inner class Phone(username: String) {
        var offline = false
        private var token: String? = null
        val api = ServerApi(http, { if (offline) "http://127.0.0.1:1" else test.base }, { token })
        val me: PlaylistPerson
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ConnectDb::class.java).allowMainThreadQueries().build().also { phones += it }
        val library: Library

        init {
            val signedIn = runBlocking { api.login(test.base, username, "correct horse", "Test phone") }
            token = signedIn.token
            me = PlaylistPerson(signedIn.user.id, signedIn.user.username, signedIn.user.displayName)
            library = Library(db, api, me = { me })
        }
    }

    @Before
    fun start() {
        test = TestServer()
        runBlocking { test.services.users.create("sam", displayName = "Sam", password = "correct horse") }
    }

    @After
    fun stop() {
        test.close()
        phones.forEach { it.close() }
    }

    @Test
    fun `a shared playlist reaches the other phone, takes their songs, and can be left`() = runBlocking {
        val maddie = Phone("maddie")
        val sam = Phone("sam")
        val song = maddie.api.search("neon").allSongs.single()
        val other = maddie.api.album(null, title = "Low Orbit", artist = "Velvet Satellites").tracks.first { it.title == "Paper Lanterns" }

        // Maddie makes a playlist and shares it with Sam (from the people on the server).
        val id = maddie.library.create("Road trip", listOf(song))
        maddie.library.sync()
        val people = maddie.library.people()
        assertEquals(setOf("admin", "sam"), people.map { it.username }.toSet())
        maddie.library.share(id, people.single { it.username == "sam" })
        val mine = maddie.library.all.first().single()
        assertTrue(mine.mine)
        assertEquals(listOf("Sam"), mine.members.map { it.name })
        assertEquals("Shared with Sam", sharedNote(mine))

        // Sam's phone brings it in, as Maddie's.
        sam.library.sync()
        val theirs = sam.library.all.first().single()
        assertEquals("Road trip", theirs.name)
        assertFalse(theirs.mine)
        assertEquals(maddie.me.id, theirs.owner?.id)
        assertEquals("Shared by maddie", sharedNote(theirs))
        assertTrue(peopleLine(theirs).startsWith("maddie's, shared with you."))
        assertEquals(listOf(maddie.me.id), sam.library.entriesNow(id).map { it.addedBy?.id })

        // Offline, Sam adds a song: it's there at once, as his, and waits for a connection.
        sam.offline = true
        sam.library.add(id, listOf(other))
        assertEquals(listOf("Neon Tidepool", "Paper Lanterns"), sam.library.entriesNow(id).map { it.track.title })
        assertEquals("sam", sam.library.entriesNow(id).last().addedBy?.username)
        assertTrue(Library.isOffline(runCatching { sam.library.sync() }.exceptionOrNull()!!))
        assertEquals(1, sam.db.pending().all().size)
        // Renaming isn't his to do: nothing changes or waits.
        sam.library.rename(id, "Sam's trip")
        assertEquals("Road trip", sam.library.all.first().single().name)
        assertEquals(1, sam.db.pending().all().size)

        // Back online, it reaches the server, and Maddie's phone sees who added what.
        sam.offline = false
        sam.library.sync()
        assertEquals(0, sam.db.pending().all().size)
        assertTrue(sam.library.entriesNow(id).none { it.entryId.startsWith("local-") })
        val onServer = test.services.playlists.get(maddie.me.id, id)
        assertEquals(listOf("Neon Tidepool", "Paper Lanterns"), onServer.entries.map { it.track.title })
        maddie.library.sync()
        assertEquals(listOf("maddie", "sam"), maddie.library.entriesNow(id).map { it.addedBy?.username })

        // Offline again, Sam leaves it: it's gone from his phone at once, and from the server's
        // list of who it's shared with once he's back. His song stays.
        sam.offline = true
        sam.library.leave(id)
        assertTrue(sam.library.all.first().isEmpty())
        assertEquals(listOf(Library.KIND_LEAVE), sam.db.pending().all().map { it.kind })
        sam.offline = false
        sam.library.sync()
        assertTrue(sam.library.all.first().isEmpty())
        assertEquals(0, sam.db.pending().all().size)
        assertTrue(test.services.playlists.list(sam.me.id).isEmpty())
        maddie.library.sync()
        val after = maddie.library.all.first().single()
        assertTrue(after.members.isEmpty())
        assertNull(sharedNote(after))
        assertEquals(2, maddie.library.entriesNow(id).size)
    }

    @Test
    fun `the owner takes someone off, and it goes from their phone`() = runBlocking {
        val maddie = Phone("maddie")
        val sam = Phone("sam")
        val id = maddie.library.create("Dinner", listOf(maddie.api.search("neon").allSongs.single()))
        val samPerson = PlaylistPerson(sam.me.id, "sam", "Sam")
        maddie.library.share(id, samPerson)
        sam.library.sync()
        assertEquals(listOf("Dinner"), sam.library.all.first().map { it.name })

        maddie.library.stopSharing(id, samPerson)
        assertTrue(maddie.library.all.first().single().members.isEmpty())
        sam.library.sync()
        assertTrue(sam.library.all.first().isEmpty())

        // Sharing needs a connection: offline, it says so rather than waiting.
        maddie.offline = true
        assertTrue(Library.isOffline(runCatching { maddie.library.people() }.exceptionOrNull()!!))
    }
}
