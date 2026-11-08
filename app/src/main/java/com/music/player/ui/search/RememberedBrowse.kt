package com.music.player.ui.search

import com.music.player.browse.Album
import com.music.player.browse.Artist
import com.music.player.browse.UnifiedSearch

/**
 * The artists and albums search found lately, with where each source has them, so their pages
 * open from those sources rather than searching for them again.
 */
class RememberedBrowse(private val size: Int = 200) {
    private val artists = lru<Artist>()
    private val albums = lru<Album>()

    fun keep(artist: Artist) = synchronized(this) { artists[UnifiedSearch.artistKey(artist.name)] = artist }

    fun keep(album: Album) = synchronized(this) { albums[UnifiedSearch.albumKey(album.title, album.artist)] = album }

    fun artist(name: String): Artist? = synchronized(this) { artists[UnifiedSearch.artistKey(name)] }

    fun album(title: String, artist: String): Album? = synchronized(this) { albums[UnifiedSearch.albumKey(title, artist)] }

    private fun <T> lru() = object : LinkedHashMap<String, T>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, T>?) = this.size > this@RememberedBrowse.size
    }
}
