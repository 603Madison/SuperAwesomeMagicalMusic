package com.music.player.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColors = darkColorScheme(
    primary = Color(0xFFC8BCFF),
    onPrimary = Color(0xFF2C1A7A),
    primaryContainer = Color(0xFF4430B8),
    secondary = Color(0xFFC9C3DC),
    tertiary = Color(0xFFFFB0C9),
    background = Color(0xFF121016),
    surface = Color(0xFF121016),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6DEFF),
    secondary = Color(0xFF605B71),
    tertiary = Color(0xFF7D5262),
)

@Composable
fun MusicTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
