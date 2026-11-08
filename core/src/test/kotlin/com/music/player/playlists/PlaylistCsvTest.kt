package com.music.player.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCsvTest {
    @Test
    fun `an exported playlist imports again as the same songs`() {
        val rows = listOf(
            PlaylistCsv.Row("Creep", "Radiohead", "Pablo Honey", 238_640, "GBAYE9200070"),
            PlaylistCsv.Row("Hello, Goodbye", "The Beatles", "Magical Mystery Tour", 210_000),
            PlaylistCsv.Row("Say \"Yes\"", "Elliott Smith, Heatmiser", null, null),
            PlaylistCsv.Row("Été indien", "Joe Dassin", "L'Album de son vivant"),
            PlaylistCsv.Row("=HYPERLINK(\"x\")", "-M-", "+44"),
            PlaylistCsv.Row("Two\nlines", "Artist"),
        )
        val csv = PlaylistCsv.write(rows)
        assertTrue(csv.startsWith("﻿Track Name,Artist Name(s),Album Name,Duration (ms),ISRC\r\n"))
        // Nothing a spreadsheet would run as a formula.
        assertTrue(csv.lines().drop(1).none { line -> line.split(',').any { it.startsWith("=") || it.startsWith("\"=") } })

        val songs = PlaylistReaders.parseText(csv)
        assertEquals(rows.size, songs.size)
        assertEquals(ListedSong("Creep", "Radiohead", 238_640, "Pablo Honey", "GBAYE9200070"), songs[0])
        assertEquals("Hello, Goodbye", songs[1].title)
        assertEquals(210_000L, songs[1].durationMs)
        assertEquals("Say \"Yes\"", songs[2].title)
        assertEquals("Elliott Smith, Heatmiser", songs[2].artist)
        assertEquals("Été indien", songs[3].title)
        assertEquals("L'Album de son vivant", songs[3].album)
        assertEquals("=HYPERLINK(\"x\")", songs[4].title)
        assertEquals("-M-", songs[4].artist)
        assertEquals("+44", songs[4].album)
        assertEquals("Two lines", songs[5].title)
    }

    @Test
    fun `file names are safe everywhere`() {
        assertEquals("Road trip.csv", PlaylistCsv.fileName("Road trip"))
        assertEquals("AC DC best.csv", PlaylistCsv.fileName("AC/DC: best?"))
        assertEquals("Playlist.csv", PlaylistCsv.fileName("  "))
    }
}
