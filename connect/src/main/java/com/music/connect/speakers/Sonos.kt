package com.music.connect.speakers

import com.music.player.speakers.sonos.SonosClient
import com.music.player.speakers.sonos.SonosGroup
import com.music.player.speakers.sonos.SonosMedia
import com.music.player.speakers.sonos.SonosPlayer
import okhttp3.OkHttpClient
import kotlin.math.roundToInt

/**
 * A Sonos speaker on the home network, or rooms playing together as a group: everything goes to
 * the group's coordinator over UPnP (the protocol is core's, shared with the server). Sonos has no
 * power switch or inputs to choose; it plays MP3, AAC, FLAC (up to 24-bit/48 kHz), ALAC, WAV and
 * Ogg Vorbis, and seeks in a file by itself.
 */
class SonosSpeaker(info: SpeakerInfo, private val http: OkHttpClient) : SpeakerControl {
    @Volatile override var info: SpeakerInfo = info
        private set

    @Volatile private var player = playerFor(info)

    override val formats = FORMATS

    override suspend fun status(): ZoneStatus {
        val volume = player.volume() ?: 0.0
        return ZoneStatus(on = true, volume = (volume * MAX_VOLUME).roundToInt(), maxVolume = MAX_VOLUME, muted = false, input = "")
    }

    // Sonos speakers are always ready to play.
    override suspend fun setPower(on: Boolean) = Unit

    override suspend fun setVolume(level: Int) = player.setVolume(level.toDouble() / MAX_VOLUME)

    override suspend fun load(media: SpeakerMedia) =
        player.load(SonosMedia(media.url, media.mime, media.title, media.artist, media.album, media.artUrl, media.durationMs, media.live))

    override suspend fun play() = player.play()

    override suspend fun pause() = player.pause()

    override suspend fun stop() = player.stop()

    override suspend fun seek(positionMs: Long): Boolean {
        player.seek(positionMs)
        return true
    }

    override suspend fun transport(): Transport = player.transport().let { Transport(it.state, it.positionMs, it.durationMs, it.uri) }

    override fun update(info: SpeakerInfo): Boolean {
        if (info.deviceId != this.info.deviceId || info.transportUrl != this.info.transportUrl) return false
        this.info = info
        player = playerFor(info)
        return true
    }

    private fun playerFor(info: SpeakerInfo) =
        SonosPlayer(SonosClient(http, info.transportUrl.removeSuffix(SonosClient.Service.AV_TRANSPORT.path)), grouped = info.rooms > 1)

    companion object {
        private const val MAX_VOLUME = 100

        /** 24-bit/48 kHz stereo is about 2,300 kbps; more than that, Sonos can't play. */
        val FORMATS = SpeakerFormats(listOf("flac", "alac", "mp3", "aac", "wav", "vorbis"), maxLosslessKbps = 2_400)

        /** A group from the household's topology as a speaker to play on (through its coordinator). */
        fun infoOf(group: SonosGroup) = SpeakerInfo(
            deviceId = group.coordinator.uuid,
            name = group.name,
            model = "Sonos",
            host = group.coordinator.host,
            transportUrl = group.coordinator.baseUrl + SonosClient.Service.AV_TRANSPORT.path,
            kind = SpeakerKind.SONOS,
            rooms = group.rooms,
        )
    }
}
