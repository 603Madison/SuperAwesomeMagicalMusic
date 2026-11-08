package com.music.connect.data

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.music.connect.ConnectApp
import com.music.connect.net.ServerException
import java.util.concurrent.TimeUnit

/**
 * Sends playlist changes and listens made offline, and brings playlists up to date, whenever
 * there's a connection; then queues downloads for playlists kept offline.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ConnectApp).container
        if (!container.prefs.current().signedIn) return Result.success()
        return try {
            container.library.sync()
            container.flushListens()
            container.keepOfflineDownloaded()
            Result.success()
        } catch (e: ServerException) {
            if (e.code == 401) container.signedOutByServer()
            Log.w(TAG, "Sync failed: ${e.message}")
            if (e.code == 401) Result.failure() else Result.retry()
        } catch (e: Exception) {
            Log.i(TAG, "Sync will try again: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val NOW = "sync-now"
        private const val PERIODIC = "sync-periodic"

        private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SyncWorker>()
                    .setConstraints(online)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }

        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).setConstraints(online).build(),
            )
        }
    }
}

/** Fetches queued downloads when the network allows (Wi-Fi only, unless that's turned off). */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as ConnectApp).container
        if (!container.prefs.current().signedIn) return Result.success()
        return if (container.downloads.runQueue()) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "downloads"

        fun enqueue(context: Context, wifiOnly: Boolean) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<DownloadWorker>()
                    .setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
