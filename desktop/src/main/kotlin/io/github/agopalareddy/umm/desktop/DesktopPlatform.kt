package io.github.agopalareddy.umm.desktop

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.SnackbarHostState
import io.github.agopalareddy.umm.ui.AppEntry
import io.github.agopalareddy.umm.ui.Platform
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URLEncoder
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Linux desktop platform calls. Per-app categories, the system accent color and the keyring come in later sub-projects. */
class DesktopPlatform(
    private val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val launcher: (List<String>) -> Unit = { ProcessBuilder(it).start() },
) : Platform {
    override fun openUrl(url: String) {
        runCatching { launcher(listOf("xdg-open", url)) }.onFailure { showMessage("Couldn't open $url") }
    }

    override fun composeEmail(to: String, subject: String, body: String): Boolean =
        runCatching { launcher(listOf("xdg-open", "mailto:$to?subject=${encode(subject)}&body=${encode(body)}")) }.isSuccess

    override fun copyText(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    override fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    override fun installedApps(): List<AppEntry> = emptyList()

    override fun appLabel(id: String): String = id

    override fun appVersion(): String = DesktopPlatform::class.java.`package`?.implementationVersion ?: "dev"

    override fun versionLine(): String = "Umm ${appVersion()} · ${System.getProperty("os.name")} ${System.getProperty("os.version")}"

    override fun is24HourClock(): Boolean {
        val pattern = (DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()) as? SimpleDateFormat)?.toPattern()
        return pattern != null && 'a' !in pattern
    }

    override fun animationsEnabled(): Boolean = true

    override fun dynamicColors(dark: Boolean): ColorScheme? = null

    /** mailto: wants %20 for spaces, not URLEncoder's +. */
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")
}
