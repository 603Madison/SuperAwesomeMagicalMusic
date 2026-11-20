package com.music.server.speakers

import java.io.IOException

/** What a speaker is asked to play: an address on this server, and what to show while it plays. */
data class SpeakerMedia(
    /** A signed address the speaker can fetch without signing in (see [SpeakerStreams]). */
    val url: String,
    /** The kind of audio at [url] (audio/mpeg, audio/flac…). */
    val mime: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    /** The cover, at an address the speaker can fetch. */
    val artUrl: String? = null,
    val durationMs: Long? = null,
    /** A station: no end, no seeking. */
    val live: Boolean = false,
    /** The speaker can seek in it by itself (the original file, which answers byte ranges). */
    val seekable: Boolean = false,
    /** Where in the song [url] starts (a song started part-way is converted from there). */
    val offsetMs: Long = 0,
    /** Part of [url] that's unique to it, to tell it from anything else the speaker plays. */
    val marker: String,
)

/** What a speaker's player is doing. */
enum class Transport { PLAYING, PAUSED, STOPPED, BUFFERING }

data class RendererStatus(
    val transport: Transport,
    /** How far into what it's playing, from the start of the address it was given. */
    val positionMs: Long,
    /** What it's playing (its address, or a description containing it): checked for our [SpeakerMedia.marker]. */
    val playing: String,
    /** 0 to 1, if it said. */
    val volume: Double? = null,
)

/**
 * A speaker (or a group of them) as the server drives it: Sonos over UPnP, or a Music Assistant
 * player through Music Assistant's server.
 */
interface Renderer {
    /** Plays [media] from its start, replacing whatever was playing. */
    suspend fun load(media: SpeakerMedia)
    suspend fun play()
    suspend fun pause()
    suspend fun stop()
    /** Jumps to [positionMs] in what's playing (only asked when [SpeakerMedia.seekable]). */
    suspend fun seek(positionMs: Long)
    /** 0 to 1. */
    suspend fun setVolume(level: Double)
    suspend fun status(): RendererStatus
}

open class SpeakerException(message: String) : IOException(message)

/** A place to play, as found on the network or on Music Assistant. */
data class SpeakerTarget(
    /** Stays the same while it's there (a Sonos group's coordinator, a Music Assistant player id). */
    val id: String,
    val name: String,
    /** Shown under its name: "Sonos", "Music Assistant · Chromecast". */
    val detail: String,
    /** The host the speaker (or Music Assistant) is at, to work out which of the server's addresses it can reach. */
    val host: String,
    /** "sonos" or "ma": which formats suit it (see [SpeakerStreams]). */
    val profile: Profile,
    /** Stays the same while the same speaker is reached the same way; a new key means a new [renderer]. */
    val rendererKey: String,
    /** This server's address as the speaker reaches it, if set (otherwise worked out from [host]). */
    val speakerUrl: String? = null,
    val renderer: () -> Renderer,
) {
    enum class Profile { SONOS, MUSIC_ASSISTANT }
}
