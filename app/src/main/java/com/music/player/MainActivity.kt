package com.music.player

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import com.music.player.ui.LocalAppContainer
import com.music.player.ui.MusicAppUi
import com.music.player.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as MusicApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent {
            MusicTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    MusicAppUi()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            container.player.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        }
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
    }

    override fun onStop() {
        container.player.disconnect()
        super.onStop()
    }
}
