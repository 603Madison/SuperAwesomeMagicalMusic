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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.music.connect.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Used where the phone has no Material You colours (Android 11 and older): a deep violet with rose accents.
private val DarkColors = darkColorScheme(
    primary = Color(0xFFCFBDFF),
    onPrimary = Color(0xFF341A86),
    primaryContainer = Color(0xFF4B33A8),
    onPrimaryContainer = Color(0xFFE9DDFF),
    secondary = Color(0xFFCDC2DB),
    onSecondary = Color(0xFF342D40),
    secondaryContainer = Color(0xFF4B4358),
    onSecondaryContainer = Color(0xFFE9DEF8),
    tertiary = Color(0xFFFFB1C8),
    onTertiary = Color(0xFF5E1133),
    tertiaryContainer = Color(0xFF7B2949),
    onTertiaryContainer = Color(0xFFFFD9E2),
    background = Color(0xFF141218),
    onBackground = Color(0xFFE6E0E9),
    surface = Color(0xFF141218),
    onSurface = Color(0xFFE6E0E9),
    surfaceVariant = Color(0xFF49454F),
    onSurfaceVariant = Color(0xFFCAC4D0),
    surfaceContainerLowest = Color(0xFF0F0D13),
    surfaceContainerLow = Color(0xFF1D1B20),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
    outline = Color(0xFF948F99),
    outlineVariant = Color(0xFF49454F),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3FD1),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9DDFF),
    onPrimaryContainer = Color(0xFF1D0060),
    secondary = Color(0xFF625B71),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8DEF8),
    onSecondaryContainer = Color(0xFF1E192B),
    tertiary = Color(0xFF8E3F5C),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFD9E2),
    onTertiaryContainer = Color(0xFF3B0719),
    background = Color(0xFFFDF7FF),
    surface = Color(0xFFFDF7FF),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F2FA),
    surfaceContainer = Color(0xFFF2ECF4),
    surfaceContainerHigh = Color(0xFFECE6EE),
    surfaceContainerHighest = Color(0xFFE6E0E9),
)

/** Softer, rounder corners throughout: cards, sheets, dialogs and artwork. */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Lexend (SIL Open Font License, see licenses/Lexend-OFL.txt), built for easy reading. */
val Lexend = FontFamily(
    Font(R.font.lexend_regular, FontWeight.Normal),
    Font(R.font.lexend_medium, FontWeight.Medium),
    Font(R.font.lexend_semibold, FontWeight.SemiBold),
    Font(R.font.lexend_bold, FontWeight.Bold),
)

private fun TextStyle.lexend() = copy(fontFamily = Lexend)

/** Lexend throughout, with bolder, tighter headings so titles stand out from details. */
private val AppTypography = Typography().run {
    Typography(
        displayLarge = displayLarge.lexend(),
        displayMedium = displayMedium.lexend(),
        displaySmall = displaySmall.lexend(),
        headlineLarge = headlineLarge.lexend(),
        headlineMedium = headlineMedium.lexend(),
        headlineSmall = headlineSmall.lexend(),
        titleLarge = titleLarge.lexend(),
        titleMedium = titleMedium.lexend(),
        titleSmall = titleSmall.lexend(),
        bodyLarge = bodyLarge.lexend(),
        bodyMedium = bodyMedium.lexend(),
        bodySmall = bodySmall.lexend(),
        labelLarge = labelLarge.lexend(),
        labelMedium = labelMedium.lexend(),
        labelSmall = labelSmall.lexend(),
    )
}.run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(fontWeight = FontWeight.Medium),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ConnectTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, shapes = AppShapes, typography = AppTypography, content = content)
}
