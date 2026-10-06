package io.github.agopalareddy.umm.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.agopalareddy.umm.auth.SignInLauncher
import io.github.agopalareddy.umm.bubble.BubbleSetup
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.ProvideUmm
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.ui.UmmTheme
import io.github.agopalareddy.umm.ui.isUmmDark
import androidx.activity.SystemBarStyle
import android.graphics.Color
import kotlinx.coroutines.launch

internal object Routes {
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

class MainActivity : ComponentActivity() {
    private var keyboardEnabled by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)
    private var bubblePageRequested by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreate (rotation) delivers the original intent again; only the first launch honours the extra.
        if (savedInstanceState == null) consumeBubblePageRequest(intent)
        enableEdgeToEdge()
        setContent {
            ProvideUmm {
                // Status and navigation bar icons follow the app's theme, not the phone's.
                val dark = isUmmDark()
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                UmmTheme {
                    Surface(Modifier.fillMaxSize()) { App() }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeBubblePageRequest(intent)
    }

    /** Takes the open-Bubble-page request off [intent] so it is handled once, however often the intent is re-read. */
    private fun consumeBubblePageRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(BubbleSetup.EXTRA_OPEN_BUBBLE_PAGE, false) == true) {
            intent.removeExtra(BubbleSetup.EXTRA_OPEN_BUBBLE_PAGE)
            bubblePageRequested = true
        }
    }

    private fun refreshSetup() {
        keyboardEnabled = getSystemService(InputMethodManager::class.java).enabledInputMethodList.any { it.packageName == packageName }
        micGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    @Composable
    private fun App() {
        val key by graph.apiKeyStore.key.collectAsStateWithLifecycle()
        val settings by graph.settings.settings.collectAsStateWithLifecycle(UmmSettings())
        val scope = rememberCoroutineScope()
        val status = SetupStatus(keyboardEnabled, micGranted, key != null)
        val nav = rememberNavController()
        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            refreshSetup()
            if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                // Permanently denied: only the system app settings can grant it now.
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }

        val back: () -> Unit = { nav.popBackStack() }
        val change: SettingsChange = { transform -> scope.launch { graph.settings.update(transform) } }
        val connect = { SignInLauncher.start(this@MainActivity) }
        val saveKey: (String) -> Unit = { graph.apiKeyStore.set(it) }

        LaunchedEffect(bubblePageRequested) {
            if (bubblePageRequested) {
                bubblePageRequested = false
                nav.navigate(Routes.BUBBLE) { launchSingleTop = true }
            }
        }

        NavHost(nav, startDestination = if (status.complete) Routes.HOME else Routes.ONBOARDING) {
            composable(Routes.ONBOARDING) {
                LaunchedEffect(status.complete) {
                    if (status.complete) nav.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
                OnboardingScreen(
                    status = status,
                    onEnableKeyboard = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onConnect = connect,
                    onPasteKey = saveKey,
                )
            }
            composable(Routes.HOME) {
                HomeScreen(
                    setupComplete = status.complete,
                    onSetup = { nav.navigate(Routes.ONBOARDING) },
                    onOpen = { nav.navigate(it) },
                )
            }
            composable(Routes.SETTINGS) { SettingsHome(settings, key != null, back) { nav.navigate(it) } }
            composable(Routes.ACCOUNT) { AccountPage(back, connect, saveKey) { graph.apiKeyStore.clear() } }
            composable(Routes.DICTATION) { DictationPage(settings, back, change) }
            composable(Routes.BUBBLE) { BubblePage(settings, back, change) }
            composable(Routes.LANGUAGES) { LanguagesPage(settings, back, change) }
            composable(Routes.APPEARANCE) { AppearancePage(settings, back, change) }
            composable(Routes.STATS) { StatsPage(settings, back, change) }
            composable(Routes.CATEGORIES) { CategoriesScreen(back) }
            composable(Routes.MODELS) { ModelsScreen(back) }
            composable(Routes.HISTORY) { HistoryScreen(back) }
        }
    }
}
