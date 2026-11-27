package com.music.server.speakers.sonos

import com.music.player.speakers.sonos.SonosClient
import com.music.player.speakers.sonos.SonosMedia
import com.music.player.speakers.sonos.SonosPlayer
import com.music.server.speakers.Renderer
import com.music.server.speakers.RendererStatus
import com.music.server.speakers.SpeakerMedia
import com.music.server.speakers.Transport

// The Sonos protocol itself (SOAP, DIDL-Lite, topology, discovery) is in core, shared with the
// phone app: see com.music.player.speakers.sonos.

/** A Sonos group, played through its coordinator. */
class SonosRenderer(client: SonosClient, grouped: Boolean) : Renderer {
    private val player = SonosPlayer(client, grouped)

    override suspend fun load(media: SpeakerMedia) {
        player.load(SonosMedia(media.url, media.mime, media.title, media.artist, media.album, media.artUrl, media.durationMs, media.live))
        play()
    }

    override suspend fun play() = player.play()

    override suspend fun pause() = player.pause()

    override suspend fun stop() = player.stop()

    override suspend fun seek(positionMs: Long) = player.seek(positionMs)

    override suspend fun setVolume(level: Double) = player.setVolume(level)

    override suspend fun status(): RendererStatus {
        val transport = player.transport()
        return RendererStatus(
            transport = when (transport.state) {
                "PLAYING" -> Transport.PLAYING
                "PAUSED_PLAYBACK" -> Transport.PAUSED
                "TRANSITIONING" -> Transport.BUFFERING
                else -> Transport.STOPPED
            },
            positionMs = transport.positionMs,
            playing = transport.uri,
            volume = runCatching { player.volume() }.getOrNull(),
        )
    }
}
