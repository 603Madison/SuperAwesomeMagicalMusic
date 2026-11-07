package com.music.player.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.music.player.ui.LocalArtworkAllowed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The artwork's most characteristic colour (its vibrant swatch, else the dominant one), used to
 * tint the player around it; null without artwork or until it has loaded.
 */
@Composable
fun rememberArtworkColor(url: String?): Color? {
    val context = LocalContext.current
    val allowed = LocalArtworkAllowed.current
    var color by remember(url) { mutableStateOf<Color?>(null) }
    LaunchedEffect(url, allowed) {
        if (url == null || (!allowed && url.startsWith("http"))) {
            color = null
            return@LaunchedEffect
        }
        color = withContext(Dispatchers.Default) {
            runCatching {
                val request = ImageRequest.Builder(context).data(com.music.player.sources.ArtworkUrls.sized(url, 96)).size(96).allowHardware(false).build()
                val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
                    ?: return@runCatching null
                val palette = Palette.from(bitmap).maximumColorCount(16).generate()
                (palette.vibrantSwatch ?: palette.dominantSwatch ?: palette.mutedSwatch)?.rgb?.let(::Color)
            }.getOrNull()
        }
    }
    return color
}

/** Black or white, whichever reads better on [background]. */
fun contentColorOn(background: Color): Color = if (background.luminance() > 0.45f) Color.Black else Color.White
