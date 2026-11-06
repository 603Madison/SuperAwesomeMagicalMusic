package com.music.player.nextcloud

import android.util.Log
import com.music.player.data.PlaylistDao
import com.music.player.net.Http
import com.music.player.settings.NextcloudConfig
import com.music.player.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SignInState {
    data object Idle : SignInState
    data object Starting : SignInState
    /** The user should finish signing in at [loginUrl] in the browser. */
    data class WaitingForBrowser(val loginUrl: String) : SignInState
    data class SignedIn(val user: String) : SignInState
    data class Failed(val message: String) : SignInState
}

/**
 * Connects Nextcloud by signing in through the browser (Login Flow v2), so SSO logins such as
 * authentik work. The app receives its own app password; playlists made before connecting
 * become the account's.
 */
class NextcloudSignIn(
    private val client: NextcloudClient,
    private val settings: SettingsRepository,
    private val playlists: PlaylistDao,
    private val sync: PlaylistSyncService,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SignInState>(SignInState.Idle)
    val state: StateFlow<SignInState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(serverUrl: String, folder: String) {
        job?.cancel()
        job = scope.launch {
            _state.value = SignInState.Starting
            try {
                val flow = client.startLogin(serverUrl)
                _state.value = SignInState.WaitingForBrowser(flow.loginUrl)
                val deadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS
                var credentials: LoginCredentials? = null
                while (credentials == null) {
                    if (System.currentTimeMillis() > deadline) throw IllegalStateException("Sign-in timed out; try again")
                    delay(POLL_INTERVAL_MS)
                    credentials = runCatching { client.pollLogin(flow) }
                        .onFailure { if (it is CancellationException) throw it else Log.w(TAG, "Poll failed", it) }
                        .getOrNull()
                }
                val previous = settings.current().nextcloud
                val config = NextcloudConfig(
                    serverUrl = Http.normalizeBaseUrl(credentials.server),
                    username = credentials.loginName,
                    appPassword = credentials.appPassword,
                    folder = folder.trim().trim('/').ifBlank { NextcloudConfig.DEFAULT_FOLDER },
                    autoSync = previous.autoSync,
                )
                val connected = config.copy(userId = client.fetchUserId(config))
                connected.owner?.let { playlists.adoptUnowned(it) }
                settings.setNextcloud(connected)
                _state.value = SignInState.SignedIn(connected.davUser)
                runCatching { sync.sync() }.onFailure { Log.w(TAG, "First sync failed", it) }
            } catch (e: CancellationException) {
                _state.value = SignInState.Idle
                throw e
            } catch (e: Exception) {
                _state.value = SignInState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        job?.cancel()
        _state.value = SignInState.Idle
    }

    suspend fun signOut() {
        val current = settings.current().nextcloud
        settings.setNextcloud(NextcloudConfig(folder = current.folder))
        _state.value = SignInState.Idle
        // Revoke the app password on the server too. If that can't be done now (offline, say),
        // it's still forgotten here and can be revoked from Nextcloud's security settings.
        if (current.isConfigured) {
            scope.launch {
                runCatching { client.revokeAppPassword(current) }
                    .onFailure { if (it !is CancellationException) Log.w(TAG, "Couldn't revoke the app password", it) }
            }
        }
    }

    private companion object {
        const val TAG = "NextcloudSignIn"
        const val POLL_INTERVAL_MS = 2_000L
        /** Nextcloud's login tokens expire after 20 minutes. */
        const val LOGIN_TIMEOUT_MS = 20 * 60_000L
    }
}
