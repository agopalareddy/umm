package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.portal.Portal
import io.github.agopalareddy.umm.linux.portal.SettingsPortal
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.types.Variant

/** The desktop's light/dark preference ([dark] null = no preference) and accent color as ARGB (null = none). */
data class Appearance(val dark: Boolean?, val accentArgb: Int?)

/** Turns the portal's `org.freedesktop.appearance` values into [Appearance] fields. */
object AppearanceMapping {
    /** `color-scheme`: 1 prefers dark, 2 prefers light, anything else no preference. */
    fun dark(colorScheme: Int): Boolean? = when (colorScheme) {
        1 -> true
        2 -> false
        else -> null
    }

    /** `accent-color`: three channels in 0..1; any channel out of range means the accent is unset. */
    fun accent(r: Double, g: Double, b: Double): Int? {
        val channels = listOf(r, g, b)
        if (channels.any { it !in 0.0..1.0 }) return null
        val (red, green, blue) = channels.map { (it * 255).roundToInt() }
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }
}

/** Reads the desktop's appearance from the Settings portal and follows its changes. */
class PortalAppearance(portal: Portal) : AutoCloseable {
    private val settings = runCatching { portal.portalObject<SettingsPortal>() }.getOrNull()
    private val _appearance = MutableStateFlow(Appearance(dark = read(COLOR_SCHEME)?.let(::colorScheme), accentArgb = read(ACCENT)?.let(::accent)))
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()

    private val changes = portal.onSignal(IFACE, "SettingChanged") { signal ->
        val params = signal.parameters
        if (params.size < 3 || params[0] != NAMESPACE) return@onSignal
        val value = unwrap(params[2])
        when (params[1]) {
            COLOR_SCHEME -> _appearance.update { it.copy(dark = colorScheme(value)) }
            ACCENT -> _appearance.update { it.copy(accentArgb = accent(value)) }
        }
    }

    private fun read(key: String): Any? {
        val portal = settings ?: return null
        return runCatching { unwrap(portal.ReadOne(NAMESPACE, key)) }
            .recoverCatching { unwrap(portal.Read(NAMESPACE, key)) }
            .getOrNull()
    }

    override fun close() {
        changes.close()
    }

    private companion object {
        const val IFACE = "org.freedesktop.portal.Settings"
        const val NAMESPACE = "org.freedesktop.appearance"
        const val COLOR_SCHEME = "color-scheme"
        const val ACCENT = "accent-color"

        fun unwrap(value: Any?): Any? = if (value is Variant<*>) unwrap(value.value) else value

        fun colorScheme(value: Any?): Boolean? = (value as? Number)?.let { AppearanceMapping.dark(it.toInt()) }

        fun accent(value: Any?): Int? {
            val parts = when (value) {
                is Struct -> value.parameters.toList()
                is Array<*> -> value.toList()
                is List<*> -> value
                else -> return null
            }
            val channels = parts.map { (unwrap(it) as? Number)?.toDouble() ?: return null }
            if (channels.size != 3) return null
            return AppearanceMapping.accent(channels[0], channels[1], channels[2])
        }
    }
}
