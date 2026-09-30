package io.github.agopalareddy.umm.ui.charts

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * Donut slice colors for the cleanup levels, in enum order: RAW, LIGHT, FORMATTED, POLISHED.
 *
 * Fixed per theme rather than read from scheme roles: under the brand dark scheme and many wallpaper palettes
 * tertiary and error are near-identical pale tints, and error red would read the top level as bad. RAW is a
 * neutral grey; the others are blue, green and amber, spread in hue so no two are close in any theme.
 */
internal object CleanupColors {
    private val onLight = listOf(Color(0xFF757575), Color(0xFF1E6FD9), Color(0xFF1E8E5A), Color(0xFFD98200))
    private val onDark = listOf(Color(0xFF8A8A8A), Color(0xFF64A8FF), Color(0xFF4DD08C), Color(0xFFFFB74D))

    /** The palette for [scheme]'s light or dark side, judged from its surface so wallpaper colors follow suit. */
    fun palette(scheme: ColorScheme): List<Color> = if (scheme.surface.luminance() < 0.5f) onDark else onLight
}
