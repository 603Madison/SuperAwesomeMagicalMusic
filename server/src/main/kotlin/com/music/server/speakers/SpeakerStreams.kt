package com.music.server.speakers

import com.music.player.model.SourceType
import com.music.player.quality.StreamQuality
import com.music.player.sources.SourceStream
import com.music.server.ApiError
import com.music.server.Services
import com.music.server.music.ApiTrack
import com.music.server.music.Artwork
import com.music.server.music.LiveProxy
import com.music.server.music.OutputFormat
import com.music.server.music.StreamPlan
import com.music.server.music.Streamer
import com.music.server.music.respondRadioStation
import com.music.server.radio.Stations
import com.music.server.sources.SourceKind
import com.music.server.util.base64Url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.header
import io.ktor.server.response.respond
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.DatagramSocket
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Base64

/**
 * What one speaker address lets a speaker fetch: one song (from one source, from one point) or
 * one station, for one person, until [expires]. Field names are short to keep addresses short.
 */
@Serializable
data class StreamGrant(
    /** The person it plays for. */
    val u: String,
    /** "song", "station" or "sxm" (a Pandora station's track). */
    val k: String,
    /** The source (or "personal", for the person's own stations). */
    val s: String,
    /** The song or station on it. */
    val i: String,
    /** Where in the song to start, in milliseconds. */
    val o: Long = 0,
    /** Which formats suit the speaker: "sonos" or "ma". */
    val p: String,
    /** Seconds since 1970 when it stops working. */
    val e: Long,
)

/**
 * Addresses speakers can play from without signing in. A speaker can't send the person's
 * sign-in, so the server hands it an address that carries a signed [StreamGrant]: it names
 * exactly one song or station, for one person, and stops working after a while. Changing any
 * part of it (another song, another source, a later expiry) breaks the signature, and it opens
 * nothing but that stream: not the API, not another song.
 *
 * `/api/speakers/stream/<grant>/<signature>.<ext>`: the extension is only for speakers that
 * look at it; the signature covers the grant.
 */
class SpeakerStreams(private val services: Services, private val clock: () -> Long = services.clock) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    /** The path (after [PATH]) for [grant]. */
    fun mint(grant: StreamGrant, extension: String): String {
        val payload = json.encodeToString(StreamGrant.serializer(), grant).toByteArray().base64Url()
        val ext = extension.lowercase().filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        return "$payload/${signature(payload)}.$ext"
    }

    /** The grant [payload] carries, if it's genuine and hasn't expired. */
    fun verify(payload: String, file: String): StreamGrant? {
        if (payload.length > MAX_PAYLOAD) return null
        val signature = file.substringBefore('.')
        if (!services.box.verifySignature(PURPOSE + payload, signature)) return null
        val grant = runCatching {
            json.decodeFromString(StreamGrant.serializer(), String(Base64.getUrlDecoder().decode(payload)))
        }.getOrNull() ?: return null
        return grant.takeIf { it.e * 1000 > clock() }
    }

    private fun signature(payload: String) = services.box.sign(PURPOSE + payload)

    /**
     * What [target] is to play for [track]: a signed address on [base] (this server as the
     * speaker reaches it), in a format it handles, with what to show while it plays.
     */
    suspend fun media(target: SpeakerTarget, base: String, userId: String, track: ApiTrack, startMs: Long): SpeakerMedia {
        val user = services.users.byId(userId)?.takeIf { !it.disabled } ?: throw SpeakerException("That account can't play here")
        val profile = target.profile.code
        val art = artFor(base, track.artworkUrl)
        val now = clock() / 1000
        fun address(grant: StreamGrant, extension: String) = base.trimEnd('/') + PATH + mint(grant, extension)
        val via = track.via
        return when {
            track.source == SourceType.SIRIUSXM && track.sourceId.startsWith("channel:") ->
                throw SpeakerException("SiriusXM channels can't play on speakers yet")
            track.source == SourceType.SIRIUSXM && track.sourceId.startsWith("stationtrack:") -> {
                via ?: throw SpeakerException("This station track has expired")
                val trackId = track.sourceId.removePrefix("stationtrack:").substringAfter(':')
                val grant = StreamGrant(userId, "sxm", via, trackId, 0, profile, now + validFor(track.durationMs))
                val url = address(grant, "m4a")
                SpeakerMedia(url, "audio/mp4", track.title, track.artist, track.album, art, track.durationMs, marker = markerOf(url))
            }
            track.source == SourceType.RADIO -> {
                via ?: throw SpeakerException("Which station?")
                if (track.sourceId.startsWith("hls:")) throw SpeakerException("This station streams in a way speakers can't play")
                val aac = via == Stations.VIA || services.sources.get(via)?.kind == SourceKind.TUNER
                val grant = StreamGrant(userId, "station", via, track.sourceId, 0, profile, now + STATION_VALID_S)
                val url = address(grant, if (aac) "aac" else "mp3")
                SpeakerMedia(
                    url, if (aac) "audio/aac" else "audio/mpeg", track.title, track.artist.ifBlank { "Radio" }, track.album, art,
                    durationMs = null, live = true, marker = markerOf(url),
                )
            }
            else -> {
                val wanted = services.artwork.incoming(track)
                val resolution = services.catalog.resolve(user, wanted.toTrack(), wanted.via, StreamQuality.Original, thorough = false)
                val plan = plan(resolution.stream, target.profile, startMs / 1000.0)
                val grant = StreamGrant(userId, "song", resolution.source.id, resolution.track.sourceId, startMs, profile, now + validFor(track.durationMs))
                val extension = plan.format?.extension ?: resolution.stream.fileExtension
                val url = address(grant, extension)
                val mime = plan.format?.contentType ?: Streamer.contentTypeFor(resolution.stream.fileExtension).withoutParameters().toString()
                SpeakerMedia(
                    url = url,
                    mime = mime,
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    artUrl = art,
                    durationMs = track.durationMs?.let { (it - startMs).coerceAtLeast(0) },
                    seekable = !plan.transcoded && resolution.stream.supportsRangeRequests && startMs == 0L,
                    offsetMs = startMs,
                    marker = markerOf(url),
                )
            }
        }
    }

    /** Sends what [payload] grants (see [mint]); anything else is "not found". */
    suspend fun respond(call: ApplicationCall, payload: String, file: String, headOnly: Boolean = false) {
        val grant = verify(payload, file) ?: throw ApiError.notFound()
        val user = services.users.byId(grant.u)?.takeIf { !it.disabled } ?: throw ApiError.notFound()
        val profile = SpeakerTarget.Profile.entries.firstOrNull { it.code == grant.p } ?: SpeakerTarget.Profile.SONOS
        if (headOnly) {
            // Some players ask before they fetch; the answer is only what's there.
            call.response.header(HttpHeaders.CacheControl, "no-store")
            call.respond(HttpStatusCode.OK, "")
            return
        }
        when (grant.k) {
            "song" -> {
                val source = services.sources.accessible(user, grant.s)?.takeIf { it.enabled } ?: throw ApiError.notFound()
                val stream = services.catalog.streamOf(source, grant.i, StreamQuality.Original)
                val start = grant.o / 1000.0
                services.streamer.respond(call, stream, plan(stream, profile, start), start) {
                    services.catalog.streamOf(source, grant.i, StreamQuality.Original, fresh = true)
                }
            }
            "station" -> respondRadioStation(services, call, user, grant.s, grant.i)
            "sxm" -> {
                val source = services.sources.accessible(user, grant.s)?.takeIf { it.enabled && it.kind == SourceKind.SIRIUSXM } ?: throw ApiError.notFound()
                val url = services.registry.siriusXm(source)?.stationTrackUrl(grant.i) ?: throw ApiError(HttpStatusCode.Gone, "Expired")
                services.live.respondFile(call, url, LiveProxy.SXM_HEADERS)
            }
            else -> throw ApiError.notFound()
        }
    }

    /**
     * How a song reaches [profile]. Sonos plays MP3, AAC (in MP4), FLAC up to 24-bit/48 kHz,
     * ALAC and WAV; anything else (YouTube's Opus, hi-res FLAC, a song started part-way) is
     * converted to FLAC or MP3. Music Assistant plays anything (it converts for its players itself).
     */
    fun plan(stream: SourceStream, profile: SpeakerTarget.Profile, start: Double): StreamPlan {
        if (profile == SpeakerTarget.Profile.MUSIC_ASSISTANT) return services.streamer.plan(stream, StreamQuality.Original, null, start)
        val codec = Streamer.codecOf(stream)
        val hiRes = codec in LOSSLESS && (stream.kbps ?: 0) > SONOS_MAX_LOSSLESS_KBPS
        val codecs = if (hiRes) setOf("mp3", "aac") else SONOS_CODECS
        val plan = services.streamer.plan(stream, StreamQuality.Original, codecs, start)
        // Converted, it's MP3 (Sonos and a raw AAC stream don't always get on) or FLAC.
        return when (plan.format) {
            OutputFormat.AAC, OutputFormat.OPUS -> StreamPlan(OutputFormat.MP3, if (codec in LOSSLESS) 320 else (plan.kbps ?: 320).coerceIn(128, 320))
            else -> plan
        }
    }

    /** The cover at an address the speaker can fetch (covers through the server need no sign-in). */
    private fun artFor(base: String, artworkUrl: String?): String? {
        val wrapped = services.artwork.wrap(artworkUrl) ?: return null
        return when {
            wrapped.startsWith(Artwork.PATH + "?") -> base.trimEnd('/') + wrapped + "&px=600"
            wrapped.startsWith(Artwork.LOCAL_ART) -> base.trimEnd('/') + wrapped
            else -> null
        }
    }

    /** How long an address for a song of [durationMs] stays good: the song, and an hour to spare for pauses. */
    private fun validFor(durationMs: Long?): Long = ((durationMs ?: DEFAULT_SONG_MS) / 1000).coerceIn(60, 4 * 3600) + SONG_SPARE_S

    companion object {
        const val PATH = "/api/speakers/stream/"
        private const val PURPOSE = "speaker-stream\n"
        private const val MAX_PAYLOAD = 2_000
        private const val DEFAULT_SONG_MS = 10 * 60_000L
        private const val SONG_SPARE_S = 3_600L
        /** A station plays for hours, and the speaker tunes in again with the same address if the connection drops. */
        const val STATION_VALID_S = 12 * 3_600L
        /** 24-bit/48 kHz stereo is about 2,300 kbps; more than that, Sonos can't play. */
        private const val SONOS_MAX_LOSSLESS_KBPS = 2_400
        private val SONOS_CODECS = setOf("flac", "alac", "mp3", "aac", "wav")
        private val LOSSLESS = setOf("flac", "alac", "wav", "aiff")

        /** The part of a speaker address that's unique to it: its signature. */
        fun markerOf(url: String): String = url.substringAfterLast('/').substringBefore('.')

        /**
         * This server's address as a speaker at [host] reaches it: the address of the network
         * the server reaches [host] through, on the server's port. Inside a container with its
         * own network that's the container's address, which speakers can't reach: set the
         * address for speakers then.
         */
        fun addressToward(host: String, port: Int): String? {
            val local = runCatching {
                DatagramSocket().use { socket ->
                    socket.connect(InetAddress.getByName(host.trim('[', ']')), 1400)
                    socket.localAddress
                }
            }.getOrNull()?.takeIf { !it.isAnyLocalAddress } ?: return null
            val text = local.hostAddress.substringBefore('%')
            return "http://${if (local is Inet6Address) "[$text]" else text}:$port"
        }
    }
}

/** The speaker profile's short name, as it goes in a [StreamGrant]. */
val SpeakerTarget.Profile.code: String get() = if (this == SpeakerTarget.Profile.MUSIC_ASSISTANT) "ma" else "sonos"

/** A station or a SiriusXM channel: no end, no seeking. */
val ApiTrack.isLiveStream: Boolean
    get() = source == SourceType.RADIO || (source == SourceType.SIRIUSXM && sourceId.startsWith("channel:"))

private fun ContentType.withoutParameters(): ContentType = ContentType(contentType, contentSubtype)
