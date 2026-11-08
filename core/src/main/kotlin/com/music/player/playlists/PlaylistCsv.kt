package com.music.player.playlists

/**
 * A playlist as a CSV file, in Exportify's columns: what this app's own playlist import reads,
 * and most services that take a playlist file (TuneMyMusic, Soundiiz…) too. Spreadsheets open
 * it as well (it starts with a byte-order mark, so Excel reads accents and other alphabets).
 */
object PlaylistCsv {
    data class Row(
        val title: String,
        val artist: String,
        val album: String? = null,
        val durationMs: Long? = null,
        val isrc: String? = null,
    )

    const val MIME = "text/csv"
    val HEADER = listOf("Track Name", "Artist Name(s)", "Album Name", "Duration (ms)", "ISRC")
    private const val BOM = "﻿"

    fun write(rows: List<Row>): String = buildString {
        append(BOM)
        append(HEADER.joinToString(",")).append("\r\n")
        rows.forEach { row ->
            append(
                listOf(row.title, row.artist, row.album.orEmpty(), row.durationMs?.takeIf { it > 0 }?.toString().orEmpty(), row.isrc.orEmpty())
                    .joinToString(",", transform = ::cell),
            ).append("\r\n")
        }
    }

    /** A file name for the playlist called [name] ("Road trip.csv"). */
    fun fileName(name: String): String {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ").replace(Regex("\\s+"), " ").trim().take(100)
        return "${safe.ifEmpty { "Playlist" }}.csv"
    }

    /**
     * One cell: on one line, quoted when it holds a comma or quote. A cell a spreadsheet would
     * take for a formula (a title starting with "=") gets a leading apostrophe, which the
     * import takes off again.
     */
    fun cell(value: String): String {
        var text = value.replace(Regex("[\\r\\n]+"), " ")
        if (text.isNotEmpty() && text[0] in FORMULA_STARTS) text = "'$text"
        return if (text.any { it == ',' || it == '"' } || text != text.trim()) "\"" + text.replace("\"", "\"\"") + "\"" else text
    }

    /** Takes off the apostrophe [cell] put before a formula-like value. */
    fun unguard(value: String): String =
        if (value.length > 1 && value[0] == '\'' && value[1] in FORMULA_STARTS) value.substring(1) else value

    private val FORMULA_STARTS = setOf('=', '+', '-', '@', '\t')
}
