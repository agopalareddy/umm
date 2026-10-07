package io.github.agopalareddy.umm.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.agopalareddy.umm.core.data.ThemeMode
import io.github.agopalareddy.umm.core.data.UmmSettings

// Brand colors, used when wallpaper colors are off or unavailable (before Android 12). Matches the launcher icon.
private val LightBrand = lightColorScheme(
    primary = Color(0xFF5B4FD6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4DFFF),
    onPrimaryContainer = Color(0xFF16006E),
    secondary = Color(0xFF5E5C71),
    secondaryContainer = Color(0xFFE4E0F9),
    onSecondaryContainer = Color(0xFF1B1A2C),
    tertiary = Color(0xFF7B5266),
    tertiaryContainer = Color(0xFFFFD8E8),
    onTertiaryContainer = Color(0xFF301122),
)

private val DarkBrand = darkColorScheme(
    primary = Color(0xFFC6BFFF),
    onPrimary = Color(0xFF2A1A9E),
    primaryContainer = Color(0xFF4335BD),
    onPrimaryContainer = Color(0xFFE4DFFF),
    secondary = Color(0xFFC8C3DC),
    secondaryContainer = Color(0xFF464459),
    onSecondaryContainer = Color(0xFFE4E0F9),
    tertiary = Color(0xFFEBB8CF),
    tertiaryContainer = Color(0xFF613B4E),
    onTertiaryContainer = Color(0xFFFFD8E8),
)

/** The app and keyboard theme, following the user's Appearance settings. */
@Composable
fun UmmTheme(content: @Composable () -> Unit) {
    val settings by LocalUmm.current.settings.settings.collectAsStateWithLifecycle(UmmSettings())
    UmmTheme(settings.themeMode, settings.dynamicColor, content)
}

/** Whether the app is dark, which can differ from the phone when the user picks Light or Dark. */
@Composable
fun isUmmDark(): Boolean {
    val settings by LocalUmm.current.settings.settings.collectAsStateWithLifecycle(UmmSettings())
    return isDark(settings.themeMode)
}

@Composable
private fun isDark(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> systemAppearance()?.dark ?: isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** The desktop's live appearance, or null on platforms without one. */
@Composable
private fun systemAppearance(): SystemAppearance? {
    val flow = LocalUmm.current.platform.systemAppearance() ?: return null
    return flow.collectAsStateWithLifecycle().value
}

@Composable
fun UmmTheme(mode: ThemeMode, dynamicColor: Boolean, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    // Read here too so an accent change recomposes the theme; dynamicColors() reads the same value.
    systemAppearance()
    val dynamic = if (dynamicColor) LocalUmm.current.platform.dynamicColors(dark) else null
    val colors = dynamic ?: if (dark) DarkBrand else LightBrand
    MaterialTheme(colorScheme = colors, content = content)
}
