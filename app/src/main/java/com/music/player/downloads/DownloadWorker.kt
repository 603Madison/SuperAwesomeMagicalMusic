package com.music.player.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.music.player.MusicApp
import com.music.player.R
import com.music.player.data.DownloadStatus
import com.music.player.quality.NetworkClass
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY_TRACK) ?: return Result.failure()
        val upgrade = inputData.getBoolean(KEY_UPGRADE, false)
        val container = (applicationContext as MusicApp).container
        val dao = container.database.downloads()
        val entity = dao.get(key) ?: return Result.success()
        if (upgrade && entity.status != DownloadStatus.COMPLETED) return Result.success()

        val policy = container.settings.current().downloads
        when (container.networkMonitor.state.value.networkClass) {
            NetworkClass.NONE -> return Result.retry()
            NetworkClass.SATELLITE -> if (!policy.allowSatellite) return Result.retry()
            NetworkClass.CELLULAR -> if (!policy.allowCellular) return Result.retry()
            NetworkClass.WIFI -> Unit
        }

        // An upgrade leaves the current copy in place (and playable) until the better one is saved.
        if (!upgrade) dao.updateProgress(key, DownloadStatus.DOWNLOADING, 0f)
        var target: File? = null
        return try {
            val selected = container.resolver.resolveForDownload(entity.track)
            if (upgrade && !Upgrades.isBetter(entity.effectiveKbps, entity.isLossless, selected.stream.kbps)) {
                dao.upsert(entity.copy(qualityCheckedAt = System.currentTimeMillis()))
                return Result.success()
            }
            runCatching { setForeground(foregroundInfo(entity.track.title)) }
                .onFailure { Log.i(TAG, "Downloading without a foreground notification", it) }
            val file = container.downloads.fileFor(key, selected.stream.fileExtension)
            target = file
            val partial = File(file.path + ".part")
            ChunkedDownloader(container.httpClient).download(
                url = selected.stream.url,
                headers = selected.stream.headers,
                target = partial,
                useRanges = selected.stream.supportsRangeRequests,
            ) { downloaded, total ->
                if (!upgrade) {
                    val progress = total?.let { (downloaded.toFloat() / it).coerceIn(0f, 1f) } ?: 0f
                    dao.updateProgress(key, DownloadStatus.DOWNLOADING, progress)
                }
            }
            if (!partial.renameTo(file)) throw IOException("Couldn't save ${file.name}")
            entity.filePath?.takeIf { it != file.path }?.let { File(it).delete() }
            val current = dao.get(key)
            if (current == null) {
                // Deleted while downloading.
                file.delete()
                return Result.success()
            }
            dao.upsert(
                current.copy(
                    status = DownloadStatus.COMPLETED,
                    progress = 1f,
                    filePath = file.path,
                    sizeBytes = file.length(),
                    qualityDescription = "${selected.track.source.label} · ${selected.stream.description}",
                    error = null,
                    kbps = selected.stream.kbps,
                    qualityCheckedAt = System.currentTimeMillis(),
                ),
            )
            Result.success()
        } catch (e: CancellationException) {
            target?.let { File(it.path + ".part").delete() }
            throw e
        } catch (e: Exception) {
            target?.let { File(it.path + ".part").delete() }
            Log.w(TAG, "Download of $key failed", e)
            when {
                upgrade -> {
                    // Keep the copy we have; try again at the next check.
                    dao.get(key)?.let { dao.upsert(it.copy(qualityCheckedAt = System.currentTimeMillis())) }
                    Result.success()
                }
                e is IOException && runAttemptCount < MAX_RETRIES -> {
                    dao.updateProgress(key, DownloadStatus.QUEUED, 0f)
                    Result.retry()
                }
                else -> {
                    dao.upsert(entity.copy(status = DownloadStatus.FAILED, progress = 0f, error = e.message ?: e.javaClass.simpleName))
                    Result.failure()
                }
            }
        }
    }

    private fun foregroundInfo(title: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Downloading for offline listening")
            .setContentText(title)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        const val KEY_TRACK = "track_key"
        const val KEY_UPGRADE = "upgrade"
        private const val TAG = "DownloadWorker"
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2001
        private const val MAX_RETRIES = 5
    }
}
