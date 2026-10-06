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
}

/** A platform-only Settings row, shown after Dictation. */
data class SettingsEntry(val icon: ImageVector, val title: String, val subtitle: String, val route: String)

/** Home's setup banner state; [switchKeyboard] adds a "Choose keyboard" button where the platform has one. */
data class HomeSetup(val complete: Boolean, val onSetup: () -> Unit, val switchKeyboard: (() -> Unit)?)

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
    }
}
