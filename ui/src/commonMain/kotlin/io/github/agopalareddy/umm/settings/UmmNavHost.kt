package io.github.agopalareddy.umm.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.ui.LocalUmm
import kotlinx.coroutines.launch

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val SETTINGS = "settings"
    const val ACCOUNT = "settings/account"
    const val DICTATION = "settings/dictation"
    const val BUBBLE = "settings/bubble"
    const val LANGUAGES = "settings/languages"
    const val APPEARANCE = "settings/appearance"
    const val CATEGORIES = "settings/categories"
    const val MODELS = "settings/models"
    const val STATS = "settings/stats"
    const val HISTORY = "history"
    const val ABOUT = "about"
}

/** A platform-only Settings row, shown after Dictation. */
data class SettingsEntry(val icon: ImageVector, val title: String, val subtitle: String, val route: String)

const val ANDROID_INTRO = "Switch to Umm in any text field and start talking. It stops when you do. " +
    "Double-tap the mic to whisper or pause as long as you like."
const val ANDROID_TRY_PLACEHOLDER = "Tap here, switch to Umm, and talk"

/**
 * Home's setup banner state; [switchKeyboard] adds a "Choose keyboard" button where the platform has one. [intro] and
 * [tryPlaceholder] explain how to dictate on this platform.
 */
data class HomeSetup(
    val complete: Boolean,
    val onSetup: () -> Unit,
    val switchKeyboard: (() -> Unit)?,
    val intro: String = ANDROID_INTRO,
    val tryPlaceholder: String = ANDROID_TRY_PLACEHOLDER,
)

/** The shared screens. Platforms add their own destinations through [platformRoutes]. */
@Composable
fun UmmNavHost(
    startRoute: String,
    home: HomeSetup,
    onConnect: () -> Unit,
    extraSettings: List<SettingsEntry> = emptyList(),
    sampleData: SampleDataActions? = null,
    nav: NavHostController = rememberNavController(),
    platformRoutes: NavGraphBuilder.(NavController) -> Unit = {},
) {
    val umm = LocalUmm.current
    val key by umm.apiKeyStore.key.collectAsStateWithLifecycle()
    val settings by umm.settings.settings.collectAsStateWithLifecycle(UmmSettings())
    val scope = rememberCoroutineScope()
    val back: () -> Unit = { nav.popBackStack() }
    val change: SettingsChange = { transform -> scope.launch { umm.settings.update(transform) } }
    val open: (String) -> Unit = { nav.navigate(it) }

    NavHost(nav, startDestination = startRoute) {
        platformRoutes(nav)
        composable(Routes.HOME) { HomeScreen(home, open) }
        composable(Routes.SETTINGS) { SettingsHome(settings, key != null, extraSettings, back, open) }
        composable(Routes.ACCOUNT) { AccountPage(back, onConnect, { umm.apiKeyStore.set(it) }) { umm.apiKeyStore.clear() } }
        composable(Routes.DICTATION) { DictationPage(settings, back, change) }
        composable(Routes.LANGUAGES) { LanguagesPage(settings, back, change) }
        composable(Routes.APPEARANCE) { AppearancePage(settings, back, change) }
        composable(Routes.STATS) { StatsPage(settings, back, change, sampleData) }
        composable(Routes.CATEGORIES) { CategoriesScreen(back) }
        composable(Routes.MODELS) { ModelsScreen(back) }
        composable(Routes.HISTORY) { HistoryScreen(back) }
        composable(Routes.ABOUT) { AboutPage(back) }
    }
}
