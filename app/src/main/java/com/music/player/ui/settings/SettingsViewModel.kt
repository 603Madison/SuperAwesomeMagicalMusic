package com.music.player.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.music.player.AppContainer
import com.music.player.data.LidarrRequestEntity
import com.music.player.lidarr.LidarrReachability
import com.music.player.settings.AuthentikConfig
import com.music.player.settings.SiriusXmConfig
import com.music.player.lidarr.LidarrServerOptions
import com.music.player.model.SourceType
import com.music.player.net.Http
import com.music.player.settings.AppSettings
import com.music.player.settings.DownloadSettings
import com.music.player.settings.JellyfinConfig
import com.music.player.settings.LidarrConfig
import com.music.player.settings.NextcloudConfig
import com.music.player.settings.QobuzConfig
import com.music.player.settings.QualitySettings
import com.music.player.settings.TranscodeCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val _account = MutableStateFlow(ActionStatus())
    val accountStatus = _account.asStateFlow()

    /** Browser intents that start authentik sign-in; the screen launches them. */
    private val _signInIntents = MutableSharedFlow<Intent>(extraBufferCapacity = 1)
    val signInIntents: SharedFlow<Intent> = _signInIntents

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

    fun signOutSiriusXm() = act(_siriusXm) {
        container.siriusXm.signOut()
        settings.setSiriusXm(SiriusXmConfig())
        "Signed out"
    }

    fun startAuthentikSignIn(issuerUrl: String, clientId: String) = act(_account) {
        val config = AuthentikConfig(issuerUrl.trim(), clientId.trim())
        settings.setAuthentik(config)
        _signInIntents.emit(container.authentik.signInIntent(config))
        "Continue in the browser…"
    }

    fun finishAuthentikSignIn(result: Intent?) = act(_account) {
        val account = container.authentik.completeSignIn(result)
        "Signed in as ${account.displayName}"
    }

    fun signOutAuthentik() = act(_account) {
        container.authentik.signOut()
        "Signed out. Your playlists stay on this device and in Nextcloud."
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

    fun connectLidarr(url: String, apiKey: String) = act(_lidarr) {
        val config = settings.current().lidarr.copy(serverUrl = Http.normalizeBaseUrl(url), apiKey = apiKey.trim())
        val version = container.lidarrClient.testConnection(config)
        _lidarrOptions.value = container.lidarrClient.serverOptions(config)
        settings.setLidarr(config)
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

    fun connectNextcloud(url: String, username: String, appPassword: String, folder: String) = act(_nextcloud) {
        val previous = settings.current().nextcloud
        val config = NextcloudConfig(
            serverUrl = Http.normalizeBaseUrl(url),
            username = username.trim(),
            appPassword = appPassword.trim(),
            folder = folder.trim().trim('/').ifBlank { NextcloudConfig.DEFAULT_FOLDER },
            autoSync = previous.autoSync,
        )
        val userId = container.nextcloud.fetchUserId(config)
        settings.setNextcloud(config.copy(userId = userId))
        val result = container.playlistSync.sync()
        "Connected. Playlists synced: $result"
    }

    fun setNextcloudAutoSync(enabled: Boolean) = launch {
        settings.setNextcloud(settings.current().nextcloud.copy(autoSync = enabled))
    }

    fun syncNow() = act(_nextcloud) { "Synced: ${container.playlistSync.sync()}" }

    fun disconnectNextcloud() = act(_nextcloud) {
        settings.setNextcloud(NextcloudConfig())
        "Disconnected. Local playlists are kept."
    }

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
