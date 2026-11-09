package com.music.player.downloads

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.music.player.data.DownloadDao
import com.music.player.data.DownloadEntity
import com.music.player.data.DownloadStatus
import com.music.player.model.Track
import com.music.player.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Offline copies of songs from any source, stored in app-private storage. */
class DownloadRepository(
    private val context: Context,
    private val dao: DownloadDao,
    private val settings: SettingsRepository,
) {
    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()
    val completedKeys: Flow<Set<String>> = dao.observeCompletedKeys().map { it.toSet() }

    val directory: File get() = File(context.filesDir, "downloads").apply { mkdirs() }

    /**
     * Downloads [tracks]. [auto] marks downloads made for a kept playlist, which are removed again
     * once no kept playlist needs them; downloading a song yourself makes it permanent.
     */
    suspend fun enqueue(tracks: List<Track>, auto: Boolean = false) {
        val constraints = constraints()
        for (track in tracks.distinctBy { it.key }.filter { !it.isMissing }) {
            val existing = dao.get(track.key)
            if (existing?.status == DownloadStatus.COMPLETED && existing.filePath?.let { File(it).exists() } == true) {
                if (!auto && existing.autoDownloaded) dao.upsert(existing.copy(autoDownloaded = false))
                continue
            }
            val inProgress = existing?.status == DownloadStatus.QUEUED || existing?.status == DownloadStatus.DOWNLOADING
            if (!inProgress) dao.upsert(
                DownloadEntity(
                    trackKey = track.key,
                    track = track,
                    status = DownloadStatus.QUEUED,
                    createdAt = existing?.createdAt ?: System.currentTimeMillis(),
                    autoDownloaded = auto && existing?.autoDownloaded != false,
                ),
            ) else if (!auto && existing?.autoDownloaded == true) {
                dao.upsert(existing.copy(autoDownloaded = false))
            }
            // KEEP leaves a running download alone but still enqueues one if its job was lost.
            val policy = if (inProgress) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
            schedule(track.key, upgrade = false, constraints, policy)
        }
    }

    /**
     * Looks for a better copy of downloaded songs on every service, replacing the file when one
     * is found. The current copy stays playable meanwhile.
     */
    suspend fun checkForBetterCopies(keys: List<String>) {
        val constraints = constraints()
        keys.forEach { schedule(it, upgrade = true, constraints, ExistingWorkPolicy.KEEP) }
    }

    private fun schedule(key: String, upgrade: Boolean, constraints: Constraints, policy: ExistingWorkPolicy) {
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(workDataOf(DownloadWorker.KEY_TRACK to key, DownloadWorker.KEY_UPGRADE to upgrade))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(workName(key), policy, request)
    }

    suspend fun delete(trackKey: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(trackKey))
        dao.get(trackKey)?.filePath?.let { File(it).delete() }
        dao.delete(trackKey)
    }

    fun fileFor(trackKey: String, extension: String): File = File(directory, "${hash(trackKey)}.${safeExtension(extension)}")

    private suspend fun constraints(): Constraints {
        val policy = settings.current().downloads
        val networkType = when {
            // Satellite links are bandwidth-constrained, which Android's job scheduler never
            // treats as "connected"; the worker enforces the network policy itself instead.
            policy.allowSatellite -> NetworkType.NOT_REQUIRED
            policy.allowCellular -> NetworkType.CONNECTED
            else -> NetworkType.UNMETERED
        }
        return Constraints.Builder().setRequiredNetworkType(networkType).setRequiresStorageNotLow(true).build()
    }

    companion object {
        /**
         * The file extension servers report ("flac", "m4a"…), kept to a few letters and digits: it
         * comes from the server, and anything else (a "../", say) could put the file elsewhere.
         */
        fun safeExtension(extension: String): String =
            extension.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }.take(5).ifEmpty { "audio" }

        const val TAG = "downloads"

        fun workName(trackKey: String) = "download:$trackKey"

        private fun hash(value: String): String =
            MessageDigest.getInstance("SHA-1").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
