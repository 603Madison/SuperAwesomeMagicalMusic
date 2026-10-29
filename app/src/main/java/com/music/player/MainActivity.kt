package com.music.player

import android.os.Bundle
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
        setContent {
            MusicTheme {
                CompositionLocalProvider(LocalAppContainer provides container) {
                    MusicAppUi()
                }
            }
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
