package com.music.player.sources.siriusxm

import com.music.player.net.AppJson
import com.music.player.net.HttpException
import com.music.player.net.call
import com.music.player.net.fetchString
import com.music.player.settings.SiriusXmConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SiriusXmAuthException(message: String) : Exception(message)

/**
 * SiriusXM live radio through the private "edge-gateway" API used by SiriusXM's web player,
 * following the flow of the open-source sxm-player / omarchy-siriusxm clients:
 * device grant → anonymous session → password identity grant → authenticated session.
 *
 * The API is undocumented and SiriusXM can change or block it at any time.
 */
class SiriusXmClient(
    private val http: OkHttpClient,
    private val credentials: suspend () -> SiriusXmConfig,
    private val loadSession: suspend () -> String?,
    private val saveSession: suspend (String?) -> Unit,
    private val baseUrl: String = API_BASE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var session: SxmSession? = null
    private val requestCounter = AtomicInteger()

    private var channelCache: List<SxmChannel>? = null
    private var channelsFetchedAt = 0L
    private val tunes = ConcurrentHashMap<String, Pair<String, Long>>()
    private val keys = ConcurrentHashMap<String, ByteArray>()
    private val schedules = ConcurrentHashMap<String, Pair<List<SxmCut>, Long>>()
    private val xtraChannels = ConcurrentHashMap<String, XtraChannel>()
    private val telemetryCounter = AtomicInteger()
    private val diagnosticLog = ArrayDeque<String>()

    private val _nowPlaying = MutableStateFlow<SxmNowPlaying?>(null)
    /** Updated by the playback service while a channel is playing. */
    val nowPlaying: StateFlow<SxmNowPlaying?> = _nowPlaying.asStateFlow()

    private val _thumbs = MutableStateFlow<Map<String, SxmThumb>>(emptyMap())
    /** Thumbs given this session, by station track id. */
    val thumbs: StateFlow<Map<String, SxmThumb>> = _thumbs.asStateFlow()

    fun publishNowPlaying(value: SxmNowPlaying?) {
        _nowPlaying.value = value
    }

    suspend fun isAvailable(): Boolean = credentials().isConfigured

    /** Signs in with new credentials (without saving them) and returns the channel count. */
    suspend fun verify(config: SiriusXmConfig): Int = mutex.withLock {
        // Keep the device registration but drop any session belonging to previous credentials.
        session = currentSession().let { SxmSession(deviceId = it.deviceId, deviceGrant = it.deviceGrant) }
        login(config)
        parseLineup(request("GET", "relationship/v1/container/all-channels", query = lineupQuery())).size
    }

    suspend fun signOut() = mutex.withLock {
        session = null
        channelCache = null
        tunes.clear()
        xtraChannels.clear()
        saveSession(null)
    }

    suspend fun channels(refresh: Boolean = false): List<SxmChannel> {
        val cached = channelCache
        if (!refresh && cached != null && clock() - channelsFetchedAt < LINEUP_MAX_AGE_MS) return cached
        val channels = parseLineup(call("GET", "relationship/v1/container/all-channels", query = lineupQuery()))
        if (channels.isEmpty()) throw IllegalStateException("SiriusXM returned an empty channel list")
        channelCache = channels
        channelsFetchedAt = clock()
        return channels
    }

    /** The channel's HLS master playlist URL; tuning is reused for a while. */
    suspend fun streamUrl(type: String, channelId: String, forceRetune: Boolean = false): String {
        tunes[channelId]?.takeIf { !forceRetune && clock() - it.second < STREAM_MAX_AGE_MS }?.let { return it.first }
        return applyTune(channelId, type, tune(type, channelId))
    }

    private suspend fun tune(type: String, channelId: String): JsonObject = call(
        "POST",
        "playback/play/v1/tuneSource",
        buildJsonObject {
            put("id", channelId)
            put("type", type)
            put("hlsVersion", "V3")
            put("manifestVariant", if (type == LINEAR) "WEB" else "FULL")
            put("mtcVersion", "V2")
            mediaFormat(type)?.let { put("mediaFormat", it) }
        },
    )

    /** The media format SiriusXM's web player asks for, by source type: MP4 files for artist stations. */
    private fun mediaFormat(type: String): String? = if (type == ARTIST_STATION) "MP4" else null

    private class StationQueue {
        var sequenceToken: String? = null
        val queued = LinkedHashSet<String>()
    }

    private val stationQueues = ConcurrentHashMap<String, StationQueue>()
    private val stationTrackUrls = ConcurrentHashMap<String, String>()

    /**
     * Tracks of a Pandora artist station: the first few when [more] is false (tuning it afresh),
     * else the next ones after those already handed out, asked for with "peek" as SiriusXM's
     * web player does.
     */
    suspend fun stationTracks(stationId: String, more: Boolean): List<SxmStationTrack> {
        val queue = stationQueues[stationId]?.takeIf { more } ?: StationQueue().also { stationQueues[stationId] = it }
        val response = if (more) {
            call(
                "POST",
                "playback/play/v1/peek",
                buildJsonObject {
                    put("id", stationId)
                    put("type", ARTIST_STATION)
                    put("hlsVersion", "V3")
                    put("mtcVersion", "V2")
                    put("manifestVariant", "FULL")
                    put("sourceContextId", "")
                    put("mediaFormat", "MP4")
                    queue.sequenceToken?.let { put("sequenceToken", it) }
                },
            )
        } else {
            tune(ARTIST_STATION, stationId)
        }
        recordDiagnostics("${if (more) "peek" else "tune"} station $stationId", response.toString())
        (response["sequenceToken"] as? JsonPrimitive)?.contentOrNull?.let { queue.sequenceToken = it }
        val now = clock()
        return (response["streams"] as? JsonArray).orEmpty().mapNotNull { element ->
            val stream = element as? JsonObject ?: return@mapNotNull null
            val id = (stream["id"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            if (!queue.queued.add(id)) return@mapNotNull null
            val (url, _) = primaryUrl(stream) ?: return@mapNotNull null
            stationTrackUrls[id] = url
            SxmStationTrack(stationId, id, url, SiriusXmParser.parseXtraItems(SiriusXmParser.trackItems(stream), now).firstOrNull())
        }
    }

    /** The audio file of a station track handed out this session, or null once it's been forgotten. */
    fun stationTrackUrl(trackId: String): String? = stationTrackUrls[trackId]

    /** Tells SiriusXM a station track started, or played for [elapsedMs]. */
    suspend fun onStationTrack(stationId: String, trackId: String, elapsedMs: Long, start: Boolean) =
        reportProgress(ARTIST_STATION, stationId, trackId, elapsedMs, start)

    /** Takes the stream URL and the schedule from a tune response. */
    private fun applyTune(channelId: String, type: String, tuned: JsonObject): String {
        recordDiagnostics("tune $type $channelId", tuned.toString())
        val url = streamOf(tuned)
        (tuned["streams"] as? JsonArray)?.firstOrNull()?.let { stream ->
            SiriusXmParser.dig(stream, "metadata", "live", "items")?.let { items ->
                schedules[channelId] = SiriusXmParser.parseCuts(items as? JsonArray) to clock()
            }
        }
        tunes[channelId] = url to clock()
        return url
    }

    /** The primary stream URL of a tune response, noting its key id for playlists that don't name one. */
    private fun streamOf(tuned: JsonObject): String {
        val stream = (tuned["streams"] as? JsonArray)?.firstOrNull() as? JsonObject
        val urls = (stream?.get("urls") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val choice = urls.firstOrNull { (it["isPrimary"] as? JsonPrimitive)?.contentOrNull == "true" } ?: urls.firstOrNull()
        val url = (choice?.get("url") as? JsonPrimitive)?.contentOrNull
            ?: throw IllegalStateException("SiriusXM returned no stream for this channel")
        (choice["encryptionKeyId"] as? JsonPrimitive)?.contentOrNull
            ?.takeIf { it.isNotBlank() && !it.startsWith("00000000-") }
            ?.let { fallbackKeyId = it }
        return url
    }

    /** The highest bitrate (bits per second) Xtra streams may use; set from the network's cap. */
    @Volatile
    var maxBitrate: Int = Int.MAX_VALUE

    private class XtraChannel(val stream: XtraStream) {
        val mutex = Mutex()
        var lastServedMs = 0L
        /** Don't ask SiriusXM for more before this (after an attempt that brought nothing new). */
        var nextRequestMs = 0L
        /** Streams already in [stream], by SiriusXM's id. */
        val added = LinkedHashSet<String>()
        /** From the latest tune or peek, sent back when peeking for more. */
        var sequenceToken: String? = null
        var sourceContextId: String? = null
        /** Where playback is on the stream's clock, as last reported by [onXtraHeard]. */
        var heardAtMs: Long? = null
        var reportedCut: SxmCut? = null
        var lastProgressMs = 0L
        /**
         * The last track's playlist, while SiriusXM had only sent part of it (no ENDLIST): fetched
         * again now and then for the rest, which would otherwise never come. (id, url, key id)
         */
        var growing: Triple<String, String, String?>? = null
        var grownAtMs = 0L
        /** The player has played everything queued and waits for more (noted once per wait). */
        var ranOut = false
    }

    /**
     * Where the player loads an Xtra channel or artist station from: a playlist served by
     * [xtraPlaylist], which stitches its tracks into one stream (see [XtraStream]).
     */
    fun xtraUri(type: String, channelId: String): String =
        "$XTRA_SCHEME://xtra/${URLEncoder.encode(type, "UTF-8")}/${URLEncoder.encode(channelId, "UTF-8")}.m3u8"

    /** Starts an Xtra channel afresh the next time it loads, e.g. after its stream failed. */
    fun resetXtra(channelId: String) {
        if (xtraChannels.remove(channelId) != null) xtraNote(channelId, "started afresh (its stream failed)")
    }

    /**
     * The playlist for an Xtra channel or artist station. SiriusXM hands these out as a queue:
     * tuning returns the next few tracks as separate streams, all of which are appended. When
     * less than [XTRA_LEAD_MS] of the queue is left to play, more are asked for with "peek", as
     * SiriusXM's web player does (tuning again would only return the same queue). A channel
     * nobody has loaded for a while starts afresh.
     */
    suspend fun xtraPlaylist(type: String, channelId: String): String {
        val now = clock()
        val channel = xtraChannels[channelId]?.takeIf { now - it.lastServedMs < XTRA_IDLE_RESET_MS }
            ?: XtraChannel(XtraStream(now)).also { fresh ->
                val idle = xtraChannels[channelId]?.let { (now - it.lastServedMs) / 1000 }
                xtraNote(channelId, if (idle != null) "started afresh: the player hadn't asked for ${idle}s" else "tuned")
                xtraChannels[channelId] = fresh
            }
        return channel.mutex.withLock {
            growLastTrack(channel, now)
            val left = remainingMs(channel, now)
            if (!channel.stream.isEmpty && left <= 0 && !channel.ranOut) {
                channel.ranOut = true
                xtraNote(channelId, "the player has played everything queued and is waiting for SiriusXM's next track")
            } else if (left > 0) {
                channel.ranOut = false
            }
            if (channel.stream.isEmpty) {
                appendStreams(type, channelId, channel, tune(type, channelId), now, "tune")
                if (channel.stream.isEmpty) throw IllegalStateException("SiriusXM sent nothing to play on this channel")
            } else if (now >= channel.nextRequestMs && remainingMs(channel, now) < XTRA_LEAD_MS) {
                val added = try {
                    appendStreams(type, channelId, channel, peek(type, channelId, channel), now, "peek")
                } catch (e: Exception) {
                    recordDiagnostics("xtra peek failed", e.toString())
                    xtraNote(channelId, "peek failed: $e")
                    0
                }.takeIf { it > 0 } ?: runCatching {
                    // Peeking didn't help: tuning again may still bring tracks not heard yet.
                    appendStreams(type, channelId, channel, tune(type, channelId), now, "tune")
                }.onFailure {
                    recordDiagnostics("xtra tune failed", it.toString())
                    xtraNote(channelId, "tune failed: $it")
                }.getOrDefault(0)
                if (added == 0) {
                    channel.nextRequestMs = now + XTRA_RETRY_MS
                    xtraNote(channelId, "nothing new from SiriusXM; asking again in ${XTRA_RETRY_MS / 1000}s (queue ends in ${remainingMs(channel, now) / 1000}s)")
                }
            }
            channel.lastServedMs = now
            channel.stream.render()
        }
    }

    /** Fetches the rest of a track SiriusXM had only sent part of (see [XtraChannel.growing]). */
    private suspend fun growLastTrack(channel: XtraChannel, now: Long) {
        val (id, url, keyId) = channel.growing ?: return
        if (now - channel.grownAtMs < XTRA_GROW_MS) return
        channel.grownAtMs = now
        try {
            val segments = channel.stream.append(id, url, SiriusXmParser.rewriteKeys(fetchPlaylist(url), keyId ?: fallbackKeyId), null, now)
            val last = channel.stream.lastTrack
            if (last == null || last.key != id || last.complete) channel.growing = null
            if (segments > 0) {
                recordDiagnostics("xtra grow $id", "$segments more segments${if (channel.growing == null) ", complete" else ""}")
                xtraNote(channelKeyOf(channel), "the last track grew by $segments segments${if (channel.growing == null) " and is complete" else ""}")
            }
        } catch (e: Exception) {
            recordDiagnostics("xtra grow $id failed", e.toString())
            xtraNote(channelKeyOf(channel), "fetching the rest of the last track failed: $e")
        }
    }

    /** How much of the queue is left to play; before playback has reported, counted from the start. */
    private fun remainingMs(channel: XtraChannel, now: Long): Long {
        val position = channel.heardAtMs ?: (channel.stream.startTimeMs + (now - channel.stream.createdAtMs))
        return channel.stream.endTimeMs - position
    }

    private suspend fun peek(type: String, channelId: String, channel: XtraChannel): JsonObject = call(
        "POST",
        "playback/play/v1/peek",
        buildJsonObject {
            put("id", channelId)
            put("type", type)
            put("hlsVersion", "V3")
            put("mtcVersion", "V2")
            put("manifestVariant", "FULL")
            put("sourceContextId", channel.sourceContextId.orEmpty())
            channel.sequenceToken?.let { put("sequenceToken", it) }
        },
    )

    /** Appends every stream in a tune or peek response that isn't already queued; returns how many. */
    private suspend fun appendStreams(
        type: String,
        channelId: String,
        channel: XtraChannel,
        response: JsonObject,
        now: Long,
        how: String,
    ): Int {
        recordDiagnostics("$how $type $channelId", response.toString())
        (response["sequenceToken"] as? JsonPrimitive)?.contentOrNull?.let { channel.sequenceToken = it }
        val streams = (response["streams"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        var added = 0
        val summary = StringBuilder()
        for (stream in streams) {
            val id = (stream["id"] as? JsonPrimitive)?.contentOrNull ?: continue
            if (id in channel.added) continue
            val (url, keyId) = primaryUrl(stream) ?: continue
            val cut = SiriusXmParser.parseXtraItems(SiriusXmParser.trackItems(stream), now).firstOrNull()
            (SiriusXmParser.dig(stream, "metadata", "xtra", "sourceContextId") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let { channel.sourceContextId = it }
            var playlistUrl = url
            var playlist = fetchPlaylist(url)
            SiriusXmParser.pickVariant(playlist, url, maxBitrate)?.let { variant ->
                playlistUrl = variant
                playlist = fetchPlaylist(variant)
            }
            val segments = channel.stream.append(id, playlistUrl, SiriusXmParser.rewriteKeys(playlist, keyId ?: fallbackKeyId), cut, now)
            // Only part of it so far: the rest is fetched as playback goes on (growLastTrack).
            val last = channel.stream.lastTrack
            channel.growing = if (last != null && last.key == id && !last.complete) Triple(id, playlistUrl, keyId) else null
            channel.grownAtMs = now
            channel.added += id
            while (channel.added.size > 500) channel.added.remove(channel.added.first())
            added++
            summary.append("\n+ ${cut?.artist} – ${cut?.title} ($id): $segments segments, ${(cut?.durationMs ?: 0) / 1000}s")
        }
        recordDiagnostics(
            "xtra $channelId",
            "$how: ${streams.size} streams, $added new, queue ends in ${remainingMs(channel, now) / 1000}s$summary",
        )
        xtraNote(
            channelId,
            "$how: ${streams.size} from SiriusXM, $added new${summary.toString().replace("\n+ ", "\n    + ")}\n    queue now ends ${remainingMs(channel, now) / 1000}s after what's being heard" +
                if (channel.growing != null) " (the last track is still arriving)" else "",
        )
        return added
    }

    /** The primary URL of one stream, with its key id. */
    private fun primaryUrl(stream: JsonObject): Pair<String, String?>? {
        val urls = (stream["urls"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val choice = urls.firstOrNull { (it["isPrimary"] as? JsonPrimitive)?.contentOrNull == "true" } ?: urls.firstOrNull()
        val url = (choice?.get("url") as? JsonPrimitive)?.contentOrNull ?: return null
        val keyId = (choice["encryptionKeyId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && !it.startsWith("00000000-") }
        return url to keyId
    }

    /**
     * The Pandora station for [artist]. Search usually returns the station itself; otherwise
     * SiriusXM's page for the artist names it, and failing that the artist's own id is tuned.
     */
    suspend fun artistStation(artist: SxmArtist): SxmStation {
        val stationId = artist.stationId ?: runCatching {
            val page = call("GET", "page/v1/page/${artist.entityType}/${artist.id}")
            recordDiagnostics("artist page ${artist.id}", page.toString())
            SiriusXmParser.findEntityId(page, ARTIST_STATION)
        }.onFailure { recordDiagnostics("artist page ${artist.id} failed", it.toString()) }.getOrNull()
        return SxmStation(id = stationId ?: artist.id, name = "${artist.name} Radio", artworkUrl = artist.artworkUrl)
    }

    /**
     * Pandora artist stations and artists matching [query], through SiriusXM's search, with the
     * request SiriusXM's web player sends. Stations come first.
     */
    suspend fun searchArtists(query: String): List<SxmArtist> {
        val response = call(
            "POST",
            "search/v1/search",
            buildJsonObject {
                put("searchString", query)
                put("filterTypes", kotlinx.serialization.json.JsonArray(emptyList()))
                put("preferredImageVariant", "default")
                put("locale", "en-US")
            },
        )
        recordDiagnostics("search \"$query\"", response.toString())
        return SiriusXmParser.parseArtists(response)
    }

    private suspend fun fetchPlaylist(url: String): String {
        val body = http.fetchString(Request.Builder().url(url).header("User-Agent", USER_AGENT).build())
        // Never parse anything else (an audio file, say) as a playlist.
        if (!body.trimStart().startsWith("#EXTM3U")) {
            recordDiagnostics("not a playlist", "${url.substringBefore('?').substringAfterLast('/')}: ${body.length} characters")
            throw IllegalStateException("SiriusXM sent something other than a playlist")
        }
        return body
    }

    /**
     * Reports the Xtra track being heard to SiriusXM, as its own players do: "start" when a
     * track begins, then progress every half minute. [heardAtMs] is the playback position as a
     * date-time on the channel's stream clock.
     */
    suspend fun onXtraHeard(type: String, channelId: String, heardAtMs: Long) {
        val channel = xtraChannels[channelId] ?: return
        channel.heardAtMs = heardAtMs
        val cut = channel.stream.cutAt(heardAtMs) ?: return
        val elapsed = (heardAtMs - cut.startMs).coerceAtLeast(0)
        val now = clock()
        if (cut.id != channel.reportedCut?.id) {
            xtraNote(channelId, "now hearing ${cut.artist} – ${cut.title} (${cut.durationMs / 1000}s); queue ends in ${remainingMs(channel, heardAtMs) / 1000}s")
            // Tell SiriusXM the previous track played to the end before the next one starts.
            channel.reportedCut?.let { reportProgress(type, channelId, it.id, it.durationMs, start = false) }
            channel.reportedCut = cut
            channel.lastProgressMs = now
            reportProgress(type, channelId, cut.id, elapsed, start = true)
        } else if (now - channel.lastProgressMs >= XTRA_PROGRESS_INTERVAL_MS) {
            channel.lastProgressMs = now
            reportProgress(type, channelId, cut.id, elapsed, start = false)
        }
    }

    /** What SiriusXM's players send about the item playing, in every telemetry action. */
    private fun telemetryAction(type: String, channelId: String, trackId: String, elapsedMs: Long) = buildJsonObject {
        put("source", buildJsonObject {
            put("type", type)
            put("id", channelId)
        })
        put("item", buildJsonObject {
            put("type", if (type == ARTIST_STATION) "artist-station-track" else "xtra-channel-track")
            put("id", trackId)
        })
        put("position", buildJsonObject { put("elapsedTimeMs", elapsedMs) })
        put("sourceTimestamp", SiriusXmParser.isoUtc(clock()))
        put("logicalClock", buildJsonObject {
            put("counter", telemetryCounter.incrementAndGet())
            put("epoch", 0)
        })
    }

    private suspend fun sendTelemetry(name: String, action: JsonElement): JsonObject = call(
        "POST",
        "playback/telemetry/v2/actions",
        buildJsonObject {
            put("actions", kotlinx.serialization.json.buildJsonArray {
                add(buildJsonObject { put(name, action) })
            })
        },
    )

    private suspend fun reportProgress(type: String, channelId: String, trackId: String, elapsedMs: Long, start: Boolean) {
        runCatching {
            val response = sendTelemetry(if (start) "start" else "progress", telemetryAction(type, channelId, trackId, elapsedMs))
            recordDiagnostics("telemetry ${if (start) "start" else "progress"} $trackId at ${elapsedMs / 1000}s", response.toString())
        }.onFailure { recordDiagnostics("telemetry failed", it.toString()) }
    }

    /**
     * Gives a Pandora station track a thumb up or down (or takes it back with [SxmThumb.NONE]),
     * which tunes what the station plays next. Sent both ways SiriusXM's web player has sent
     * thumbs: as a feedback signal and as a telemetry feedback action. Returns whether SiriusXM
     * took it.
     */
    suspend fun rateStationTrack(stationId: String, trackId: String, thumb: SxmThumb, elapsedMs: Long): Boolean {
        val previous = _thumbs.value[trackId]
        setThumb(trackId, thumb)
        val signal = runCatching {
            call(
                "POST",
                "playback/signal/v1/feedback",
                buildJsonObject {
                    put("feedbackType", thumb.feedbackType)
                    put("item", buildJsonObject { put("stationTrack", trackId) })
                    put("context", buildJsonObject {
                        put("playback", buildJsonObject {
                            put("source", buildJsonObject {
                                put("type", ARTIST_STATION)
                                put("id", stationId)
                            })
                            put("sourceTimestamp", SiriusXmParser.isoUtc(clock()))
                            put("contentDeliveryMethod", "IP")
                        })
                    })
                },
            )
        }
        recordDiagnostics("feedback ${thumb.feedbackType} $trackId", signal.fold({ it.toString() }, { it.toString() }))
        val telemetry = runCatching {
            sendTelemetry("feedback", buildJsonObject { put(thumb.telemetryName, telemetryAction(ARTIST_STATION, stationId, trackId, elapsedMs)) })
        }
        recordDiagnostics("telemetry ${thumb.telemetryName} $trackId", telemetry.fold({ it.toString() }, { it.toString() }))
        val sent = signal.isSuccess || telemetry.isSuccess
        if (!sent) setThumb(trackId, previous ?: SxmThumb.NONE)
        return sent
    }

    private fun setThumb(trackId: String, thumb: SxmThumb) {
        _thumbs.value = if (thumb == SxmThumb.NONE) _thumbs.value - trackId else _thumbs.value + (trackId to thumb)
    }

    /** What SiriusXM sent recently, for troubleshooting (no passwords or session tokens). */
    /**
     * What happened lately: the Xtra timeline (a short line per event, for following a channel
     * that stalls), then, with [details], what SiriusXM answered.
     */
    fun diagnostics(details: Boolean = true): String {
        val parts = mutableListOf<String>()
        synchronized(xtraLog) { if (xtraLog.isNotEmpty()) parts += "Xtra timeline (UTC, newest last):\n" + xtraLog.joinToString("\n") }
        if (details) synchronized(diagnosticLog) {
            if (diagnosticLog.isNotEmpty()) parts += "What SiriusXM answered (newest last):\n\n" + diagnosticLog.joinToString("\n\n")
        }
        return parts.joinToString("\n\n")
    }

    private val xtraLog = ArrayDeque<String>()

    private fun xtraNote(channelId: String, text: String) {
        synchronized(xtraLog) {
            xtraLog.addLast("${SiriusXmParser.isoUtc(clock()).substring(11, 19)} $channelId: $text")
            while (xtraLog.size > 400) xtraLog.removeFirst()
        }
    }

    private fun channelKeyOf(channel: XtraChannel): String = xtraChannels.entries.firstOrNull { it.value === channel }?.key ?: "?"

    fun recordDiagnostics(label: String, detail: String) {
        synchronized(diagnosticLog) {
            diagnosticLog.addLast("[${SiriusXmParser.isoUtc(clock())}] $label\n${detail.take(6_000)}")
            while (diagnosticLog.size > 40) diagnosticLog.removeFirst()
        }
    }

    /** A key id from the tune response, for playlists whose key lines don't name one. */
    @Volatile
    var fallbackKeyId: String? = null
        private set

    /** The 16-byte AES-128 key for an encrypted segment. */
    suspend fun key(guid: String): ByteArray {
        keys[guid]?.let { return it }
        val data = call("GET", "playback/key/v1/$guid")
        val key = Base64.getDecoder().decode((data["key"] as? JsonPrimitive)?.contentOrNull.orEmpty())
        if (key.size != 16) throw IllegalStateException("Unexpected SiriusXM key length ${key.size}")
        keys[guid] = key
        return key
    }

    /** The song (or show) on [channelId] at wall-clock time [heardAtMs]. */
    suspend fun cutAt(type: String, channelId: String, heardAtMs: Long): SxmCut? {
        if (type != LINEAR) return xtraChannels[channelId]?.stream?.cutAt(heardAtMs)
        val cached = schedules[channelId]
        var cut = cached?.let { SiriusXmParser.currentCut(it.first, heardAtMs) }
        val stale = cached == null || clock() - cached.second > SCHEDULE_MAX_AGE_MS ||
            (cut != null && heardAtMs > cut.endMs + 5_000)
        if (stale) {
            val now = clock()
            val data = call(
                "POST",
                "playback/play/v1/liveUpdate",
                buildJsonObject {
                    put("channelId", channelId)
                    put("startTimestamp", SiriusXmParser.isoUtc(now - 3 * 3600_000L))
                    put("endTimestamp", SiriusXmParser.isoUtc(now + 60_000L))
                },
            )
            val cuts = SiriusXmParser.parseCuts(data["items"] as? JsonArray)
            schedules[channelId] = cuts to now
            cut = SiriusXmParser.currentCut(cuts, heardAtMs)
        }
        return cut
    }

    /**
     * The songs played on [channelId] before the one on at [heardAtMs], most recent first: from
     * the channel's schedule (the last three hours), or what an Xtra channel has played.
     */
    suspend fun recentCuts(type: String, channelId: String, heardAtMs: Long, limit: Int = 25): List<SxmCut> {
        val current = cutAt(type, channelId, heardAtMs)
        val before = current?.startMs ?: heardAtMs
        val cuts = if (type == LINEAR) schedules[channelId]?.first.orEmpty() else xtraChannels[channelId]?.stream?.played().orEmpty()
        return cuts.filter { it.isSong && it.startMs < before }.sortedByDescending { it.startMs }.distinctBy { it.id }.take(limit)
    }

    private fun parseLineup(raw: JsonObject) = SiriusXmParser.parseChannels(raw)

    private fun lineupQuery() = mapOf(
        "containerId" to "3JoBfOCIwo6FmTpzM1S2H7",
        "useCuratedContext" to "false",
        "entityType" to "curated-grouping",
        "entityId" to "403ab6a5-d3c9-4c2a-a722-a94a6a5fd056",
        "offset" to "0",
        "size" to "1000",
        "setStyle" to "small_list",
        "key" to Base64.getEncoder().encodeToString(UUID.randomUUID().toString().toByteArray()),
    )

    /**
     * Authenticated request, signing in again once if the session was rejected. Only signing in
     * holds [mutex]: requests run side by side, so a slow one (telemetry on satellite, say) never
     * holds up a stream key the player is waiting for.
     */
    private suspend fun call(
        method: String,
        path: String,
        payload: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
    ): JsonObject {
        val token = signedInToken()
        return try {
            request(method, path, payload, query, token)
        } catch (e: HttpException) {
            if (e.code != 401 && e.code != 403) throw e
            request(method, path, payload, query, signedInToken(rejected = token))
        }
    }

    /** A token to send, signing in first if needed. [rejected] is one SiriusXM just refused. */
    private suspend fun signedInToken(rejected: String? = null): String? = mutex.withLock {
        val config = credentials()
        if (!config.isConfigured) throw SiriusXmAuthException("SiriusXM isn't set up")
        // Sign in afresh, unless another request already did since this token was refused.
        if (rejected != null && bearer(session) == rejected) {
            session = session?.let { SxmSession(deviceId = it.deviceId, deviceGrant = it.deviceGrant) }
        }
        login(config)
        bearer(session)
    }

    private suspend fun currentSession(): SxmSession {
        session?.let { return it }
        val restored = loadSession()?.let { runCatching { AppJson.decodeFromString(SxmSession.serializer(), it) }.getOrNull() }
        return (restored ?: SxmSession()).also { session = it }
    }

    private suspend fun update(transform: (SxmSession) -> SxmSession) {
        val updated = transform(currentSession())
        session = updated
        saveSession(AppJson.encodeToString(SxmSession.serializer(), updated))
    }

    private fun valid(token: String?, expiresMs: Long) = token != null && expiresMs - clock() > TOKEN_MARGIN_MS

    /** Brings the session up to an authenticated access token. Call with [mutex] held. */
    private suspend fun login(config: SiriusXmConfig) {
        var state = currentSession()
        if (valid(state.accessToken, state.accessExpiresMs)) return

        if (state.deviceGrant == null) {
            val device = request(
                "POST",
                "device/v1/devices",
                buildJsonObject {
                    put("devicePlatform", "web-desktop")
                    put("deviceAttributes", buildJsonObject {
                        put("browser", buildJsonObject {
                            put("browserVersion", BROWSER_VERSION)
                            put("browser", "Chrome")
                            put("userAgent", USER_AGENT)
                            put("sdk", "web")
                            put("app", "web")
                            put("sdkVersion", BROWSER_VERSION)
                            put("appVersion", BROWSER_VERSION)
                        })
                    })
                },
                token = null,
            )
            val grant = device.string("grant") ?: throw SiriusXmAuthException("SiriusXM did not issue a device grant")
            update { SxmSession(deviceId = device.string("deviceId"), deviceGrant = grant) }
            state = currentSession()
        }

        if (state.identityGrant == null || !valid(state.anonToken, state.anonExpiresMs)) {
            update { SxmSession(deviceId = it.deviceId, deviceGrant = it.deviceGrant) }
            val anonymous = request("POST", "session/v1/sessions/anonymous", JsonPrimitive(""))
            update {
                it.copy(
                    anonToken = anonymous.string("accessToken"),
                    anonExpiresMs = expiry(anonymous),
                )
            }
            val identity = try {
                request(
                    "POST",
                    "identity/v1/identities/authenticate/password",
                    buildJsonObject {
                        put("handle", config.username)
                        put("password", config.password)
                    },
                )
            } catch (e: HttpException) {
                if (e.code in listOf(400, 401, 403, 404, 422)) throw SiriusXmAuthException("SiriusXM rejected that email or password")
                throw e
            }
            val grant = identity.string("grant") ?: throw SiriusXmAuthException("SiriusXM did not accept the sign-in")
            update { it.copy(identityGrant = grant) }
        }

        val authenticated = request("POST", "session/v1/sessions/authenticated", JsonPrimitive(""))
        val token = authenticated.string("accessToken") ?: throw SiriusXmAuthException("SiriusXM did not open a session")
        update { it.copy(accessToken = token, accessExpiresMs = expiry(authenticated)) }
    }

    private fun expiry(response: JsonObject): Long =
        SiriusXmParser.parseTimeMs(response["accessTokenExpiresAt"]) ?: (clock() + 3600_000L)

    private fun bearer(state: SxmSession?): String? = when {
        state == null -> null
        valid(state.accessToken, state.accessExpiresMs) -> state.accessToken
        state.identityGrant != null && valid(state.anonToken, state.anonExpiresMs) -> state.identityGrant
        valid(state.anonToken, state.anonExpiresMs) -> state.anonToken
        else -> state.deviceGrant
    }

    /** One request to the API, sending [token] (by default, the session's current one) if any. */
    private suspend fun request(
        method: String,
        path: String,
        payload: JsonElement? = null,
        query: Map<String, String> = emptyMap(),
        token: String? = bearer(session),
    ): JsonObject {
        val url = (baseUrl + path).toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .header("Origin", WEB_ORIGIN)
            .header("Referer", "$WEB_ORIGIN/")
            .header("x-sxm-clock", "[0,${requestCounter.incrementAndGet()}]")
            .header("x-sxm-platform", "browser")
            .header("x-sxm-tenant", "sxm")
            .apply {
                token?.let { header("Authorization", "Bearer $it") }
            }
            .method(method, payload?.toString()?.toRequestBody(JSON))
            .build()
        val body = http.call(request) { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw HttpException(response.code, "SiriusXM $path failed: HTTP ${response.code}")
            text
        }
        if (body.isBlank()) return JsonObject(emptyMap())
        return AppJson.parseToJsonElement(body).jsonObject
    }

    private fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    companion object {
        const val LINEAR = "channel-linear"
        /** Pandora artist stations, a source type SiriusXM's apps tune like channels. */
        const val ARTIST_STATION = "artist-station"
        const val API_BASE = "https://api.edge-gateway.siriusxm.com/"
        const val WEB_ORIGIN = "https://www.siriusxm.com"
        private const val BROWSER_VERSION = "130.0.0.0"
        const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
        private val JSON = "application/json; charset=UTF-8".toMediaType()
        private const val TOKEN_MARGIN_MS = 10 * 60_000L
        private const val LINEUP_MAX_AGE_MS = 24 * 3600_000L
        private const val STREAM_MAX_AGE_MS = 20 * 60_000L
        private const val SCHEDULE_MAX_AGE_MS = 3 * 60_000L
        const val XTRA_SCHEME = "sxmxtra"
        /** Ask for more tracks when less than this is left to play, and wait this long between attempts. */
        private const val XTRA_LEAD_MS = 60_000L
        private const val XTRA_RETRY_MS = 10_000L
        private const val XTRA_GROW_MS = 8_000L
        private const val XTRA_PROGRESS_INTERVAL_MS = 30_000L
        /** The player reloads a live playlist every few seconds; a longer silence means it stopped. */
        private const val XTRA_IDLE_RESET_MS = 2 * 60_000L
    }
}
