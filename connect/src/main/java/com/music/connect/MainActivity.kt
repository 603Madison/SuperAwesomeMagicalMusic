package com.music.connect

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.music.connect.data.SyncWorker
import com.music.connect.playback.PlayerConnection
import com.music.connect.ui.LocalPlayer
import com.music.connect.ui.MainScaffold
import com.music.connect.ui.SignInScreen
import com.music.connect.ui.theme.ConnectTheme
import com.music.connect.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.launch

/** The scrims Android's default edge-to-edge puts behind three-button navigation. */
private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    private lateinit var player: PlayerConnection
    private val finishing = mutableStateOf(false)
    private val signInError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val c = container
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { c.http })) }.build()
        }
        player = PlayerConnection(this) { c.settings.value.server }
        player.connect()
        handle(intent)
        setContent {
            val settings by c.settings.collectAsState()
            // The status and navigation bars' icons follow the app's theme, which may not be the phone's.
            val dark = isAppInDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            ConnectTheme(settings.themeMode, settings.wallpaperColors) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalPlayer provides player) {
                        if (settings.signedIn && !finishing.value) MainScaffold() else SignInScreen(finishing.value, signInError.value)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        if (container.settings.value.signedIn) SyncWorker.enqueue(this)
        // A speaker playing from the phone while the app was in the background: keep it going.
        com.music.connect.speakers.SpeakerService.keepAliveIfPlaying(this)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    /** The server's sign-in page sends the app back here with its one-time code. */
    private fun handle(intent: Intent?) {
        val link = intent?.data ?: return
        if (link.scheme != "sammusic" || link.host != "signed-in") return
        finishing.value = true
        signInError.value = null
        lifecycleScope.launch {
            try {
                container.signIn.finish(link)
                SyncWorker.enqueue(this@MainActivity)
            } catch (e: Exception) {
                signInError.value = e.message ?: "Couldn't finish signing in"
            }
            finishing.value = false
        }
    }
}
