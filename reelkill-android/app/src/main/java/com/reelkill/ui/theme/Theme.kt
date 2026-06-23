package com.reelkill.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

// Using default serif/sans to match the web extension's font fallbacks
val SerifFontFamily = FontFamily.Serif
val SansFontFamily = FontFamily.Default

private val EditorialColorScheme = lightColorScheme(
    primary = Accent,
    onPrimary = BgSurface,
    primaryContainer = AccentBg,
    onPrimaryContainer = Accent,
    background = BgPage,
    onBackground = TextPrimary,
    surface = BgSurface,
    onSurface = TextPrimary,
    surfaceVariant = BgSoft,
    onSurfaceVariant = TextSecondary,
    outline = BorderHairline,
    error = Critical,
    onError = BgSurface
)

@Composable
fun ReelKillTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = EditorialColorScheme,
        content = content
    )
}
