package io.github.agopalareddy.umm.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.runtime.Composable
import io.github.agopalareddy.umm.ui.LocalUmm

/** Version and links; the desktop sidebar's About. Android shows these rows on its Settings page instead. */
@Composable
fun AboutPage(onBack: (() -> Unit)?, onOpen: (String) -> Unit) {
    val platform = LocalUmm.current.platform
    Page("About", onBack) {
        NavRow(Icons.Default.Info, "Version", platform.appVersion()) {}
        NavRow(Icons.Default.NewReleases, "What's new", null) { onOpen(Routes.WHATS_NEW) }
        NavRow(Icons.Default.Code, "Source on GitHub", null) { platform.openUrl("https://github.com/agopalareddy/umm") }
        NavRow(Icons.Default.Lock, "Privacy policy", null) { platform.openUrl(PRIVACY_POLICY_URL) }
        Section("Support Umm") {
            NavRow(Icons.Default.Favorite, "Buy Me a Coffee", "One-off support") { platform.openUrl(BUY_ME_A_COFFEE_URL) }
            NavRow(Icons.Default.Favorite, "Ko-fi", "0% fee tips & monthly support") { platform.openUrl(KO_FI_URL) }
            NavRow(Icons.Default.Favorite, "Patreon", "Monthly membership") { platform.openUrl(PATREON_URL) }
        }
    }
}
