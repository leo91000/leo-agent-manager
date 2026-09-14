package dev.leo.manager.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Semantic tokens from src/styles/theme.css. Keep the web brand while using
// Android's native typography, touch targets, shapes and interaction patterns.
internal val LeoLightColors =
    lightColorScheme(
        primary = Color(0xFF4545EF),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFEEEEFE),
        onPrimaryContainer = Color(0xFF4545EF),
        inversePrimary = Color(0xFFB8B2FF),
        secondary = Color(0xFF727187),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFEEEEFE),
        onSecondaryContainer = Color(0xFF28283C),
        tertiary = Color(0xFF8986A4),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFF4F3F7),
        onTertiaryContainer = Color(0xFF28283C),
        background = Color(0xFFFDFCFE),
        onBackground = Color(0xFF28283C),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF28283C),
        surfaceVariant = Color(0xFFF4F3F7),
        onSurfaceVariant = Color(0xFF727187),
        surfaceTint = Color(0xFF4545EF),
        inverseSurface = Color(0xFF222228),
        inverseOnSurface = Color(0xFFEEEEF2),
        surfaceBright = Color.White,
        surfaceDim = Color(0xFFE6E5EB),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color.White,
        surfaceContainer = Color(0xFFF4F3F7),
        surfaceContainerHigh = Color(0xFFEEEEFE),
        surfaceContainerHighest = Color(0xFFE6E5EB),
        outlineVariant = Color(0xFFE6E5EB),
        outline = Color(0xFF727187),
        error = Color(0xFFA64034),
        onError = Color.White,
        errorContainer = Color(0xFFFFF1EF),
        onErrorContainer = Color(0xFFA64034),
    )
internal val LeoDarkColors =
    darkColorScheme(
        primary = Color(0xFFB8B2FF),
        onPrimary = Color(0xFF28283C),
        primaryContainer = Color(0xFF313047),
        onPrimaryContainer = Color(0xFFB8B2FF),
        inversePrimary = Color(0xFF4545EF),
        secondary = Color(0xFFAAAAB6),
        onSecondary = Color(0xFF222228),
        secondaryContainer = Color(0xFF313047),
        onSecondaryContainer = Color(0xFFEEEEF2),
        tertiary = Color(0xFF9898A5),
        onTertiary = Color(0xFF222228),
        tertiaryContainer = Color(0xFF303038),
        onTertiaryContainer = Color(0xFFEEEEF2),
        background = Color(0xFF1B1B20),
        onBackground = Color(0xFFEEEEF2),
        surface = Color(0xFF222228),
        onSurface = Color(0xFFEEEEF2),
        surfaceVariant = Color(0xFF303038),
        onSurfaceVariant = Color(0xFFAAAAB6),
        surfaceTint = Color(0xFFB8B2FF),
        inverseSurface = Color(0xFFFFFFFF),
        inverseOnSurface = Color(0xFF28283C),
        surfaceBright = Color(0xFF303038),
        surfaceDim = Color(0xFF18181D),
        surfaceContainerLowest = Color(0xFF18181D),
        surfaceContainerLow = Color(0xFF222228),
        surfaceContainer = Color(0xFF1B1B20),
        surfaceContainerHigh = Color(0xFF292930),
        surfaceContainerHighest = Color(0xFF303038),
        outlineVariant = Color(0xFF35353E),
        outline = Color(0xFFAAAAB6),
        error = Color(0xFFEFAD9D),
        onError = Color(0xFF3B2825),
        errorContainer = Color(0xFF3B2825),
        onErrorContainer = Color(0xFFEFAD9D),
    )

@Composable
fun leoDarkTheme(preference: String): Boolean =
    when (preference) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

@Composable
fun LeoTheme(preference: String = "system", content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (leoDarkTheme(preference)) LeoDarkColors else LeoLightColors,
        content = content,
    )
}
