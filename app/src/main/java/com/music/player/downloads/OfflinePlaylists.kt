package com.music.player.downloads

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.player.MusicApp
import com.music.player.data.DownloadDao
import com.music.player.data.DownloadStatus
import com.music.player.data.MissingDetails
import com.music.player.data.PlaylistDao
import com.music.player.model.SourceType
import com.music.player.model.Track
import com.music.player.sources.MusicSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.TimeUnit

/** When a download counts as replaceable by a better copy. */
object Upgrades {
    private const val LOSSLESS_KBPS = 1000

    fun isBetter(currentKbps: Int?, currentLossless: Boolean, candidateKbps: Int?): Boolean {
        if (candidateKbps == null) return false
        val current = currentKbps ?: return false
        val candidateLossless = candidateKbps >= LOSSLESS_KBPS
        val losslessNow = currentLossless || current >= LOSSLESS_KBPS
        return when {
            // Lossless to lossless only for hi-res (24-bit) over CD quality.
            losslessNow && candidateLossless -> candidateKbps >= 2 * maxOf(current, 1411)
            losslessNow -> false
            candidateLossless -> true
            // Lossy: worth it from 15% more bitrate (e.g. 128 -> 160 kbps, 160 -> 320 kbps).
            else -> candidateKbps >= current * 1.15
        }
    }

    /**
     * Whether a Jellyfin copy should replace a download from another service: your own library
     * is preferred unless the download is clearly better (e.g. a Qobuz FLAC over a 128 kbps MP3).
     */
    fun preferJellyfin(currentKbps: Int?, jellyfinKbps: Int?): Boolean {
        if (currentKbps == null || jellyfinKbps == null) return true
        return !isBetter(jellyfinKbps, jellyfinKbps >= LOSSLESS_KBPS, currentKbps)
    }
}

/** What "Refresh downloads" found: [inJellyfin] is null when Jellyfin couldn't be asked. */
data class RefreshResult(val songs: Int, val inJellyfin: Int?, val rechecking: Int, val queued: Int, val artworkFound: Int = 0)

/**
 * Keeps playlists marked "keep downloaded" available offline: new songs are downloaded, songs
 * are re-checked about weekly for a better copy on any service, and automatic downloads no
 * kept playlist needs any more are removed (songs you downloaded yourself stay).
 */
class OfflinePlaylists(
    private val playlists: PlaylistDao,
    private val downloadDao: DownloadDao,
    private val downloads: DownloadRepository,
    private val jellyfin: MusicSource,
    private val details: MissingDetails? = null,
    /** Another service's copy with artwork, for songs saved without (see [MissingDetails]). */
    private val findCopy: suspend (Track) -> Track? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun sync() {
        val wanted = playlists.keptEntries().map { it.track }.distinctBy { it.key }
        val wantedKeys = wanted.map { it.key }.toSet()
        val existing = downloadDao.all().associateBy { it.trackKey }

        downloads.enqueue(
            wanted.filter { track ->
                val download = existing[track.key]
                download == null || download.status == DownloadStatus.FAILED
            },
            auto = true,
        )

        val due = existing.values
            .filter { it.trackKey in wantedKeys && it.status == DownloadStatus.COMPLETED }
            .filter { (it.qualityCheckedAt ?: 0L) < clock() - RECHECK_MS }
            .sortedBy { it.qualityCheckedAt ?: 0L }
            .take(MAX_CHECKS_PER_RUN)
        downloads.checkForBetterCopies(due.map { it.trackKey })

        existing.values.filter { it.autoDownloaded && it.trackKey !in wantedKeys }.forEach { downloads.delete(it.trackKey) }
    }

    /**
     * "Refresh downloads" on a playlist: finds songs from other services that Jellyfin now has
     * (e.g. once Lidarr brought in the album), and re-checks every downloaded song now instead of
     * waiting for the weekly check, so downloads switch to the Jellyfin copy or a better one. In a
     * kept playlist, songs that aren't downloaded (or failed) are queued again.
     */
    suspend fun refresh(playlistId: String): RefreshResult {
        val playlist = playlists.get(playlistId)
        val tracks = playlists.entries(playlistId).map { it.track }.distinctBy { it.key }
        val existing = downloadDao.all().associateBy { it.trackKey }
        val completed = tracks.mapNotNull { existing[it.key]?.takeIf { d -> d.status == DownloadStatus.COMPLETED } }

        val elsewhere = tracks.filter { track ->
            val download = existing[track.key]?.takeIf { it.status == DownloadStatus.COMPLETED }
            (download?.downloadedFrom ?: track.source) != SourceType.JELLYFIN && !track.isLive
        }
        val inJellyfin = if (jellyfin.isAvailable()) {
            val permits = Semaphore(LOOKUP_CONCURRENCY)
            coroutineScope {
                elsewhere.map { track ->
                    async { permits.withPermit { runCatching { jellyfin.findMatch(track) }.getOrNull() != null } }
                }.awaitAll().count { it }
            }
        } else {
            null
        }

        val artworkFound = details?.let { filler ->
            val permits = Semaphore(LOOKUP_CONCURRENCY)
            coroutineScope {
                tracks.filter { it.artworkUrl == null && !it.isLive }.map { track ->
                    async {
                        permits.withPermit {
                            val copy = runCatching { findCopy(track) }.getOrNull() ?: return@withPermit false
                            filler.fill(track, copy) != track
                        }
                    }
                }.awaitAll().count { it }
            }
        } ?: 0

        downloads.checkForBetterCopies(completed.map { it.trackKey })
        val missing = if (playlist?.keepDownloaded == true) {
            tracks.filter { existing[it.key] == null || existing[it.key]?.status == DownloadStatus.FAILED }
        } else {
            emptyList()
        }
        downloads.enqueue(missing, auto = true)
        return RefreshResult(
            songs = tracks.size,
            inJellyfin = inJellyfin,
            rechecking = completed.size,
            queued = missing.size,
            artworkFound = artworkFound,
        )
    }

    companion object {
        private const val LOOKUP_CONCURRENCY = 4
        private const val RECHECK_MS = 7 * 24 * 3600_000L
        private const val MAX_CHECKS_PER_RUN = 25
    }
}

/** Runs [OfflinePlaylists.sync] after playlist changes and periodically. */
class OfflinePlaylistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MusicApp).container
        // Songs imported playlists named that weren't found: looked for again, in case they've turned up.
        runCatching { container.playlistImporter.findMissing() }
        container.offlinePlaylists.sync()
        return Result.success()
    }

    companion object {
        private const val ONE_TIME = "offline-playlists"
        private const val PERIODIC = "offline-playlists-periodic"

        fun enqueue(context: Context, delaySeconds: Long = 5) {
            val request = OneTimeWorkRequestBuilder<OfflinePlaylistWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<OfflinePlaylistWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
