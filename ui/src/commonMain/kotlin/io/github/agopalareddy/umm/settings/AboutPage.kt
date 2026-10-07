package io.github.agopalareddy.umm.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import io.github.agopalareddy.umm.ui.LocalUmm

/** Version and links; the desktop sidebar's About. Android shows these rows on its Settings page instead. */
@Composable
fun AboutPage(onBack: (() -> Unit)?) {
    val platform = LocalUmm.current.platform
    Page("About", onBack) {
        NavRow(Icons.Default.Info, "Version", platform.appVersion()) {}
        NavRow(Icons.Default.Code, "Source on GitHub", null) { platform.openUrl("https://github.com/agopalareddy/umm") }
        NavRow(Icons.Default.Lock, "Privacy policy", null) { platform.openUrl(PRIVACY_POLICY_URL) }
    }
}
