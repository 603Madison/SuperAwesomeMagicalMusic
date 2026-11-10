package com.music.connect.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.music.connect.R
import com.music.connect.data.ThemeMode

/**
 * The app's look, shared with the other apps (SuperAwesomeMagicalWeather, Cockpit Manager): Lexend
 * throughout, cool slate neutrals with rounded cards over a soft gradient, light and dark, and the
 * phone's wallpaper colours if wanted. Each app has its own accent: this one has the web player's
 * violet, with rose for favourites.
 */

/** Whether the app is drawn dark (the theme mode, or the phone's). */
val LocalIsDark = staticCompositionLocalOf { false }

/** The gradient behind every screen: a faint wash of the accent at the top. */
val LocalBackdrop = staticCompositionLocalOf { Brush.verticalGradient(listOf(Color(0xFFECE7FB), Color(0xFFF5F7FB))) }

/**
 * The web player's violet (its --primary), the same whatever colours the phone uses: for what
 * should look alike on every device, like another device being in control.
 */
val SitePurple: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDark.current) Color(0xFFCFBDFF) else Color(0xFF5B3FD1)

/** The web player's rose (its --accent): the favourite heart, alike everywhere. */
val SiteRose: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDark.current) Color(0xFFFFB1C8) else Color(0xFF8E3F5C)

/** For content on [SitePurple]. */
val OnSitePurple: Color
    @Composable @ReadOnlyComposable get() = if (LocalIsDark.current) Color(0xFF341A86) else Color.White

/** Violet and rose over the same cool slate neutrals as the other apps. */
private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF1D0060),
    secondary = Color(0xFF5C5791),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E0FA),
    onSecondaryContainer = Color(0xFF1B1748),
    tertiary = Color(0xFF8E3F5C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3B0719),
    background = Color(0xFFF5F7FB),
    onBackground = Color(0xFF111827),
    surface = Color(0xFFF5F7FB),
    onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFE4E9F2),
    onSurfaceVariant = Color(0xFF4B5565),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBFCFE),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color(0xFFEEF2F8),
    surfaceContainerHighest = Color(0xFFE6EBF3),
    outline = Color(0xFFB8C1CF),
    outlineVariant = Color(0xFFDDE3EC),
    error = Color(0xFFC62828),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBDFF),
    onPrimary = Color(0xFF341A86),
    primaryContainer = Color(0xFF4B33A8),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFC5C1F5),
    onSecondary = Color(0xFF2D2960),
    secondaryContainer = Color(0xFF3D3A72),
    onSecondaryContainer = Color(0xFFE3E0FA),
    tertiary = Color(0xFFFFB1C8),
    onTertiary = Color(0xFF5E1133),
    tertiaryContainer = Color(0xFF7B2949),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFE5E9F0),
    surface = Color(0xFF0B1220),
    onSurface = Color(0xFFE5E9F0),
    surfaceVariant = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFFA9B4C6),
    surfaceContainerLowest = Color(0xFF070C16),
    surfaceContainerLow = Color(0xFF0F1828),
    surfaceContainer = Color(0xFF131D30),
    surfaceContainerHigh = Color(0xFF1A2539),
    surfaceContainerHighest = Color(0xFF223047),
    outline = Color(0xFF3B4A63),
    outlineVariant = Color(0xFF263349),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0000),
    errorContainer = Color(0xFF6B1515),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Softer, rounder corners throughout, as in the other apps. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Lexend (SIL Open Font License, see licenses/Lexend-OFL.txt), built for easy reading. */
val Lexend = FontFamily(
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_medium, FontWeight.Medium),
    Font(R.font.lexend_semibold, FontWeight.SemiBold),
    Font(R.font.lexend_bold, FontWeight.Bold),
)

private fun TextStyle.lexend() = copy(fontFamily = Lexend)

/** Material's scale with the same weights as the other apps, all in Lexend. */
private val AppTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.lexend(),
        displayMedium = displayMedium.lexend(),
        displaySmall = displaySmall.lexend(),
        headlineLarge = headlineLarge.lexend(),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp).lexend(),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp).lexend(),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold).lexend(),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold).lexend(),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold).lexend(),
        bodyLarge = bodyLarge.lexend(),
        bodyMedium = bodyMedium.lexend(),
        bodySmall = bodySmall.lexend(),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold).lexend(),
        labelMedium = labelMedium.lexend(),
        labelSmall = labelSmall.lexend(),
    )
}

@Composable
fun isAppInDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/**
 * The app's theme: its own violet over slate by default, or the phone's wallpaper colours
 * (Android 12 and later) when [wallpaper] is on.
 */
@Composable
fun ConnectTheme(mode: ThemeMode = ThemeMode.SYSTEM, wallpaper: Boolean = false, content: @Composable () -> Unit) {
    val dark = isAppInDarkTheme(mode)
    val useWallpaper = wallpaper && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val colors = when {
        useWallpaper && dark -> dynamicDarkColorScheme(context)
        useWallpaper -> dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    val backdrop = when {
        useWallpaper -> Brush.verticalGradient(listOf(colors.primaryContainer.copy(alpha = 0.35f).compositeOver(colors.background), colors.background))
        dark -> Brush.verticalGradient(listOf(Color(0xFF17122E), Color(0xFF0B1220)))
        else -> Brush.verticalGradient(listOf(Color(0xFFECE7FB), Color(0xFFF5F7FB)))
    }
    CompositionLocalProvider(LocalIsDark provides dark, LocalBackdrop provides backdrop) {
        MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
    }
}
