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
import com.music.player.data.PlaylistDao
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
}

/**
 * Keeps playlists marked "keep downloaded" available offline: new songs are downloaded, songs
 * are re-checked about weekly for a better copy on any service, and automatic downloads no
 * kept playlist needs any more are removed (songs you downloaded yourself stay).
 */
class OfflinePlaylists(
    private val playlists: PlaylistDao,
    private val downloadDao: DownloadDao,
    private val downloads: DownloadRepository,
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

    companion object {
        private const val RECHECK_MS = 7 * 24 * 3600_000L
        private const val MAX_CHECKS_PER_RUN = 25
    }
}

/** Runs [OfflinePlaylists.sync] after playlist changes and periodically. */
class OfflinePlaylistWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        (applicationContext as MusicApp).container.offlinePlaylists.sync()
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
