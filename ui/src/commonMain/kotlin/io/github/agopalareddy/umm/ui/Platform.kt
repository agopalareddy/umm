package io.github.agopalareddy.umm.ui

import androidx.compose.material3.ColorScheme

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
    fun is24HourClock(): Boolean

    /** False when the system asks for no animations, so charts draw their final state. */
    fun animationsEnabled(): Boolean

    /** The system's dynamic color scheme, or null where there is none. */
    fun dynamicColors(dark: Boolean): ColorScheme?
}

/** An app on this device; [id] is the package name on Android, so stored assignments keep working. */
data class AppEntry(val id: String, val label: String)
