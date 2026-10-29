package com.music.player.nextcloud

import android.content.Context
import android.util.Log
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
import com.music.player.data.PlaylistDao
import com.music.player.data.PlaylistEntity
import com.music.player.data.PlaylistEntryEntity
import com.music.player.net.AppJson
import com.music.player.net.Http
import com.music.player.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

data class SyncResult(val downloaded: Int, val uploaded: Int, val deletedLocally: Int, val deletedRemotely: Int) {
    override fun toString() =
        "$downloaded downloaded, $uploaded uploaded, ${deletedLocally + deletedRemotely} deleted"
}

sealed interface SyncStatus {
    data object Idle : SyncStatus
    data object Running : SyncStatus
    data class Done(val result: SyncResult, val at: Long) : SyncStatus
    data class Failed(val message: String, val at: Long) : SyncStatus
}

/** What to do with a playlist file found on Nextcloud, given the local copy (if any). */
enum class RemoteAction { IMPORT, OVERWRITE_LOCAL, KEEP_LOCAL, IGNORE_DELETED }

/**
 * Two-way sync between local playlists and JSON files in a Nextcloud folder. Each playlist is
 * one `<name>.json` file containing its id, so renames and edits made on other devices are
 * recognised. Conflicts resolve by last modification time.
 */
class PlaylistSyncService(
    private val dao: PlaylistDao,
    private val client: NextcloudClient,
    private val settings: SettingsRepository,
) {
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.Idle)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    suspend fun sync(): SyncResult = mutex.withLock {
        _status.value = SyncStatus.Running
        try {
            runSync().also { _status.value = SyncStatus.Done(it, System.currentTimeMillis()) }
        } catch (e: Exception) {
            _status.value = SyncStatus.Failed(e.message ?: e.javaClass.simpleName, System.currentTimeMillis())
            throw e
        }
    }

    private suspend fun runSync(): SyncResult {
        val current = settings.current()
        val owner = current.account?.subject
        // Signed-in users get their own subfolder, named after their authentik user id.
        val config = current.nextcloud.let { nc ->
            if (owner == null) nc else nc.copy(folder = "${nc.folder.trim('/')}/${folderNameFor(owner)}")
        }
        check(config.isConfigured) { "Nextcloud is not set up" }
        val target = listOf(Http.normalizeBaseUrl(config.serverUrl), config.davUser, config.folder.trim('/'), owner.orEmpty())
            .joinToString("|")
        if (settings.lastSyncTarget() != target) {
            // A different server, folder or user: treat this user's playlists as never synced, so
            // nothing is mistaken for "deleted on another device".
            dao.resetSyncState(owner)
            dao.purgeTombstones(owner)
            settings.setLastSyncTarget(target)
        }
        client.ensureFolder(config)
        val remote = client.list(config).filter { it.name.endsWith(".json", ignoreCase = true) }.associateBy { it.name }
        var downloaded = 0
        var uploaded = 0
        var deletedLocally = 0
        var deletedRemotely = 0

        // 1. Pull files that are new or changed since we last saw them.
        val byFileName = dao.all(owner).filter { it.remoteFileName != null }.associateBy { it.remoteFileName!! }
        for (file in remote.values) {
            val known = byFileName[file.name]
            if (known != null && file.etag != null && known.remoteEtag == file.etag) continue
            val (body, etag) = client.download(config, file.name)
            val doc = runCatching { AppJson.decodeFromString<PlaylistDocument>(body) }.getOrNull()
                ?.takeIf { it.format == PlaylistDocument.FORMAT } ?: continue
            val local = dao.get(doc.id)
            val remoteEtag = etag ?: file.etag
            when (decide(local, doc.updatedAt)) {
                RemoteAction.IMPORT, RemoteAction.OVERWRITE_LOCAL -> {
                    dao.replace(
                        PlaylistEntity(
                            id = doc.id,
                            name = doc.name,
                            description = doc.description,
                            createdAt = doc.createdAt,
                            updatedAt = doc.updatedAt,
                            remoteFileName = file.name,
                            remoteEtag = remoteEtag,
                            syncedUpdatedAt = doc.updatedAt,
                            ownerId = owner,
                        ),
                        doc.tracks.mapIndexed { index, track -> PlaylistEntryEntity(doc.id, index, track) },
                    )
                    downloaded++
                }
                // Local edits are newer; they are uploaded below over this file.
                RemoteAction.KEEP_LOCAL -> dao.upsert(local!!.copy(remoteFileName = file.name, remoteEtag = remoteEtag))
                // Deleted here; the file is removed below.
                RemoteAction.IGNORE_DELETED -> dao.upsert(local!!.copy(remoteFileName = file.name, remoteEtag = remoteEtag))
            }
        }

        // 2. Push local changes; drop playlists whose file was deleted on another device.
        val takenNames = remote.keys.toMutableSet()
        for (playlist in dao.all(owner).filter { !it.deleted }) {
            val onServer = playlist.remoteFileName != null && playlist.remoteFileName in remote
            if (playlist.remoteFileName != null && !onServer && !playlist.needsUpload) {
                dao.purge(playlist.id)
                deletedLocally++
                continue
            }
            if (!playlist.needsUpload && onServer) continue

            val fileName = fileNameFor(playlist, takenNames - setOfNotNull(playlist.remoteFileName))
            val document = PlaylistDocument(
                id = playlist.id,
                name = playlist.name,
                description = playlist.description,
                createdAt = playlist.createdAt,
                updatedAt = playlist.updatedAt,
                tracks = dao.entries(playlist.id).map { it.track },
                owner = owner,
            )
            val etag = client.upload(config, fileName, AppJson.encodeToString(PlaylistDocument.serializer(), document))
            val previous = playlist.remoteFileName
            if (previous != null && previous != fileName && previous in remote) {
                client.delete(config, previous)
                takenNames -= previous
            }
            takenNames += fileName
            dao.upsert(playlist.copy(remoteFileName = fileName, remoteEtag = etag, syncedUpdatedAt = playlist.updatedAt))
            uploaded++
        }

        // 3. Push local deletions.
        for (playlist in dao.all(owner).filter { it.deleted }) {
            playlist.remoteFileName?.takeIf { it in remote }?.let {
                client.delete(config, it)
                deletedRemotely++
            }
            dao.purge(playlist.id)
        }
        return SyncResult(downloaded, uploaded, deletedLocally, deletedRemotely)
    }

    companion object {
        private val ILLEGAL_CHARS = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        /** authentik ids are usually hex hashes; keep anything else safe as a folder name. */
        fun folderNameFor(owner: String): String = owner.replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun decide(local: PlaylistEntity?, remoteUpdatedAt: Long): RemoteAction = when {
            local == null -> RemoteAction.IMPORT
            local.deleted -> RemoteAction.IGNORE_DELETED
            local.needsUpload && local.updatedAt >= remoteUpdatedAt -> RemoteAction.KEEP_LOCAL
            else -> RemoteAction.OVERWRITE_LOCAL
        }

        /** "Road Trip" -> "Road Trip.json"; adds part of the id when another playlist has that name. */
        fun fileNameFor(playlist: PlaylistEntity, taken: Set<String>): String {
            val base = playlist.name.replace(ILLEGAL_CHARS, "_").trim().trim('.').take(100).ifEmpty { "Playlist" }
            val plain = "$base.json"
            return if (plain !in taken || plain == playlist.remoteFileName) plain else "$base (${playlist.id.take(8)}).json"
        }
    }
}

/** Runs [PlaylistSyncService.sync] in the background when the device is online. */
class PlaylistSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val container = (applicationContext as MusicApp).container
        if (!container.settings.current().nextcloud.isConfigured) return Result.success()
        return try {
            container.playlistSync.sync()
            Result.success()
        } catch (e: Exception) {
            Log.w("PlaylistSyncWorker", "Playlist sync failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val ONE_TIME = "playlist-sync"
        private const val PERIODIC = "playlist-sync-periodic"

        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        /** Debounced sync after a local edit. */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<PlaylistSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(5, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONE_TIME, ExistingWorkPolicy.REPLACE, request)
        }

        fun schedulePeriodic(context: Context, enabled: Boolean) {
            val workManager = WorkManager.getInstance(context)
            if (!enabled) {
                workManager.cancelUniqueWork(PERIODIC)
                return
            }
            val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()
            workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
