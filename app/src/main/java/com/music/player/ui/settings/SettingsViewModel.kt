package com.music.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.player.AppContainer
import com.music.player.data.LidarrRequestEntity
import com.music.player.lidarr.LidarrReachability
import com.music.player.nextcloud.SignInState
import com.music.player.settings.SiriusXmConfig
import com.music.player.lidarr.LidarrServerOptions
import com.music.player.model.SourceType
import com.music.player.net.Http
import com.music.player.net.HttpException
import com.music.player.settings.AppSettings
import com.music.player.settings.DownloadSettings
import com.music.player.settings.JellyfinConfig
import com.music.player.settings.LidarrConfig
import com.music.player.settings.NextcloudConfig
import com.music.player.settings.QobuzConfig
import com.music.player.settings.QualitySettings
import com.music.player.settings.TranscodeCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

data class ActionStatus(val running: Boolean = false, val message: String? = null, val isError: Boolean = false)

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    private val settings = container.settings
    val current: StateFlow<AppSettings> = container.settingsState

    private val _jellyfin = MutableStateFlow(ActionStatus())
    val jellyfinStatus = _jellyfin.asStateFlow()
    private val _qobuz = MutableStateFlow(ActionStatus())
    val qobuzStatus = _qobuz.asStateFlow()
    private val _lidarr = MutableStateFlow(ActionStatus())
    val lidarrStatus = _lidarr.asStateFlow()
    private val _nextcloud = MutableStateFlow(ActionStatus())
    val nextcloudStatus = _nextcloud.asStateFlow()

    private val _siriusXm = MutableStateFlow(ActionStatus())
    val siriusXmStatus = _siriusXm.asStateFlow()
    val nextcloudSignIn: StateFlow<SignInState> = container.nextcloudSignIn.state

    val lidarrQueue: StateFlow<List<LidarrRequestEntity>> =
        container.lidarr.pending.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val lidarrReachability: StateFlow<LidarrReachability> = container.lidarr.reachability

    private val _lidarrOptions = MutableStateFlow<LidarrServerOptions?>(null)
    val lidarrOptions: StateFlow<LidarrServerOptions?> = _lidarrOptions.asStateFlow()

    init {
        // Load Lidarr's profiles so the pickers show names, not ids.
        viewModelScope.launch {
            val lidarr = settings.current().lidarr
            if (lidarr.isConfigured) {
                runCatching { container.lidarrClient.serverOptions(lidarr) }.onSuccess { _lidarrOptions.value = it }
            }
        }
    }

    fun signInJellyfin(url: String, username: String, password: String) = act(_jellyfin) {
        val config = container.jellyfin.authenticate(url, username, password)
        settings.setJellyfin(config)
        "Signed in as ${config.username}"
    }

    fun signOutJellyfin() = act(_jellyfin) {
        settings.setJellyfin(JellyfinConfig())
        "Signed out"
    }

    fun signInQobuz(appId: String, appSecret: String, username: String, password: String, token: String) = act(_qobuz) {
        val config = if (token.isNotBlank()) {
            QobuzConfig(appId.trim(), appSecret.trim(), username.trim(), token.trim()).also { container.qobuz.verify(it) }
        } else {
            container.qobuz.login(appId.trim(), appSecret.trim(), username.trim(), password)
        }
        settings.setQobuz(config)
        "Signed in to Qobuz"
    }

    fun signOutQobuz() = act(_qobuz) {
        settings.setQobuz(QobuzConfig())
        "Signed out"
    }

    fun signInSiriusXm(username: String, password: String) = act(_siriusXm) {
        val config = SiriusXmConfig(username.trim(), password)
        val channels = container.siriusXm.verify(config)
        settings.setSiriusXm(config)
        "Signed in · $channels channels"
    }

    val version: String get() = container.versionDescription

    /** The app log (Android Auto requests and playback errors), headed by the version. */
    fun appDiagnostics(): String =
        "Music ${container.versionDescription}\n" + container.appLog.text().ifBlank { "Nothing recorded yet." }

    fun siriusXmDiagnostics(): String = container.siriusXm.diagnostics().ifBlank { "Nothing recorded yet. Play a channel first." }

    fun signOutSiriusXm() = act(_siriusXm) {
        container.siriusXm.signOut()
        settings.setSiriusXm(SiriusXmConfig())
        "Signed out"
    }

    fun retryLidarrQueue() = act(_lidarr) {
        val done = container.lidarr.process()
        when (val reachability = container.lidarr.reachability.value) {
            is LidarrReachability.Unreachable -> throw IllegalStateException("Can't reach Lidarr: ${reachability.reason}")
            else -> if (done == 0) "Nothing sent" else "Sent $done ${if (done == 1) "request" else "requests"} to Lidarr"
        }
    }

    fun removeLidarrRequest(artistKey: String) = container.lidarr.remove(artistKey)

    fun setYouTubeMusicEnabled(enabled: Boolean) = launch { settings.setYouTubeMusicEnabled(enabled) }

    fun preferFirst(type: SourceType) = launch {
        val order = settings.current().fallbackOrder
        settings.setFallbackOrder(listOf(type) + order.filter { it != type })
    }

    fun setQuality(quality: QualitySettings) = launch { settings.setQuality(quality) }

    fun setTranscodeCodec(codec: TranscodeCodec) = launch { settings.setTranscodeCodec(codec) }

    fun setDownloads(downloads: DownloadSettings) = launch { settings.setDownloads(downloads) }

    fun setLoadArtworkOnSatellite(enabled: Boolean) = launch { settings.setLoadArtworkOnSatellite(enabled) }

    fun setBufferAheadSeconds(seconds: Int) = launch { settings.setBufferAheadSeconds(seconds) }

    fun setPlayback(playback: com.music.player.settings.PlaybackSettings) = launch { settings.setPlayback(playback) }

    fun connectLidarr(url: String, apiKey: String) = act(_lidarr) {
        val config = settings.current().lidarr.copy(serverUrl = Http.normalizeBaseUrl(url), apiKey = apiKey.trim())
        val version = try {
            container.lidarrClient.testConnection(config)
        } catch (e: HttpException) {
            throw IllegalStateException(if (e.code == 401) "Lidarr rejected the API key" else "Lidarr answered HTTP ${e.code}")
        } catch (e: IOException) {
            // Usually just not on the home network: keep the settings, requests will wait.
            settings.setLidarr(config)
            return@act "Saved. Lidarr isn't reachable from this network right now; requests will wait until it is."
        }
        _lidarrOptions.value = container.lidarrClient.serverOptions(config)
        settings.setLidarr(config)
        container.lidarr.processInBackground()
        "Connected to Lidarr $version"
    }

    fun updateLidarr(transform: (LidarrConfig) -> LidarrConfig) = launch {
        settings.setLidarr(transform(settings.current().lidarr))
    }

    fun disconnectLidarr() = act(_lidarr) {
        settings.setLidarr(LidarrConfig())
        _lidarrOptions.value = null
        "Disconnected"
    }

    /** Opens Nextcloud's login page in the browser (works with SSO such as authentik). */
    fun signInToNextcloud(url: String, folder: String) = container.nextcloudSignIn.start(url, folder)

    fun cancelNextcloudSignIn() = container.nextcloudSignIn.cancel()

    fun setNextcloudFolder(folder: String) = act(_nextcloud) {
        settings.setNextcloud(settings.current().nextcloud.copy(folder = folder.trim().trim('/').ifBlank { NextcloudConfig.DEFAULT_FOLDER }))
        "Synced: ${container.playlistSync.sync()}"
    }

    fun setNextcloudAutoSync(enabled: Boolean) = launch {
        settings.setNextcloud(settings.current().nextcloud.copy(autoSync = enabled))
    }

    fun syncNow() = act(_nextcloud) { "Synced: ${container.playlistSync.sync()}" }

    fun disconnectNextcloud() = act(_nextcloud) {
        container.nextcloudSignIn.signOut()
        "Signed out. Your playlists stay on this phone and come back when you sign in again."
    }

    // Playback cache
    private val _cacheUsed = MutableStateFlow(0L)
    val cacheUsedBytes = _cacheUsed.asStateFlow()

    fun refreshCacheUsage() = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) { _cacheUsed.value = container.playbackCache.usedBytes }

    fun setCache(cache: com.music.player.settings.CacheSettings) = viewModelScope.launch {
        settings.setCache(cache)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { container.playbackCache.trim() }
        refreshCacheUsage()
    }

    fun clearCache() = viewModelScope.launch {
        container.playbackCache.clear()
        refreshCacheUsage()
    }

    // Scrobbling
    private val _scrobbling = MutableStateFlow(ActionStatus())
    val scrobblingStatus = _scrobbling.asStateFlow()
    val pendingScrobbles: StateFlow<Int> =
        container.database.scrobbles().observeCount().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val lidarrWaiting = container.lidarr.waitingForDownloads.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun connectListenBrainz(url: String, token: String) = act(_scrobbling) {
        val base = url.trim().ifBlank { com.music.player.settings.ScrobbleSettings.DEFAULT_LISTENBRAINZ_URL }
        val user = com.music.player.scrobble.ListenBrainzClient(container.httpClient).validate(base, token.trim())
        settings.setScrobbling(settings.current().scrobbling.copy(listenBrainzUrl = Http.normalizeBaseUrl(base), listenBrainzToken = token.trim()))
        "Scrobbling to ListenBrainz as ${user.ifBlank { "you" }}"
    }

    fun signInLastFm(apiKey: String, secret: String, username: String, password: String) = act(_scrobbling) {
        val session = com.music.player.scrobble.LastFmClient(container.httpClient).signIn(apiKey.trim(), secret.trim(), username.trim(), password)
        settings.setScrobbling(
            settings.current().scrobbling.copy(
                lastFmApiKey = apiKey.trim(),
                lastFmSecret = secret.trim(),
                lastFmUser = username.trim(),
                lastFmSession = session,
            ),
        )
        "Scrobbling to Last.fm as ${username.trim()}"
    }

    fun disconnectListenBrainz() = launch { settings.setScrobbling(settings.current().scrobbling.copy(listenBrainzToken = "")) }

    fun disconnectLastFm() = launch {
        settings.setScrobbling(settings.current().scrobbling.copy(lastFmApiKey = "", lastFmSecret = "", lastFmUser = "", lastFmSession = ""))
    }

    fun setScrobbleRadio(include: Boolean) = launch { settings.setScrobbling(settings.current().scrobbling.copy(includeRadio = include)) }

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }

    private fun act(status: MutableStateFlow<ActionStatus>, block: suspend () -> String) {
        viewModelScope.launch {
            status.value = ActionStatus(running = true)
            status.value = try {
                ActionStatus(message = block())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ActionStatus(message = e.message ?: e.javaClass.simpleName, isError = true)
            }
        }
    }
}
