package io.github.agopalareddy.umm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

class AndroidPlatform(private val context: Context) : Platform {
    override fun openUrl(url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    override fun composeEmail(to: String, subject: String, body: String): Boolean {
        val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, body)
        return runCatching { context.startActivity(mail) }.isSuccess
    }

    override fun copyText(text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umm", text))
    }

    override fun showMessage(text: String) {
        // Longer messages (like the no-email-app fallback) need more time to read.
        Toast.makeText(context, text, if (text.length > 40) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    override fun installedApps(): List<AppEntry> {
        val pm = context.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.id }
            .filter { it.id != context.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun is24HourClock(): Boolean = DateFormat.is24HourFormat(context)

    override fun animationsEnabled(): Boolean =
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) != 0f

    override fun dynamicColors(dark: Boolean): ColorScheme? = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> null
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
}
