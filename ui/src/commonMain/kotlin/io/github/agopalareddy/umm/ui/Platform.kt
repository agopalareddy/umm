package io.github.agopalareddy.umm.ui

import androidx.compose.material3.ColorScheme
import kotlinx.coroutines.flow.StateFlow

/** The platform calls the shared screens make. */
interface Platform {
    fun openUrl(url: String)

    /** Opens a draft in the user's email app; false when there is none. */
    fun composeEmail(to: String, subject: String, body: String): Boolean
    fun copyText(text: String)

    /** A short, transient message (a toast on Android). */
    fun showMessage(text: String)

    /** Apps the user can assign to categories, excluding Umm itself. */
    fun installedApps(): List<AppEntry>

    /** The display name of the app with [id], or [id] itself when unknown. */
    fun appLabel(id: String): String
    /** Umm's version name, e.g. "1.2.0". */
    fun appVersion(): String

    /** App and OS versions for support emails, e.g. "Umm 1.2.0 · Android 16". */
    fun versionLine(): String
    fun is24HourClock(): Boolean

    /** False when the system asks for no animations, so charts draw their final state. */
    fun animationsEnabled(): Boolean

    /** The system's dynamic color scheme, or null where there is none. */
    fun dynamicColors(dark: Boolean): ColorScheme?

    /**
     * The desktop's live light/dark and accent preferences, or null where the platform's own theming APIs apply
     * (Android). The theme recomposes when it changes.
     */
    fun systemAppearance(): StateFlow<SystemAppearance>? = null
}

/** [dark] null means no preference; [accentArgb] null means no accent color. */
data class SystemAppearance(val dark: Boolean?, val accentArgb: Int?)

/** An app on this device; [id] is the package name on Android, so stored assignments keep working. */
data class AppEntry(val id: String, val label: String)
