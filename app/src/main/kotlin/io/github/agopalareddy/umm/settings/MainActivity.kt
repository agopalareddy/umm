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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import io.github.agopalareddy.umm.core.data.UmmSettings
import io.github.agopalareddy.umm.graph
import kotlinx.coroutines.launch

internal object Routes {
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val CATEGORIES = "categories"
    const val MODELS = "models"
    const val HISTORY = "history"
}

class MainActivity : ComponentActivity() {
    private var keyboardEnabled by mutableStateOf(false)
    private var micGranted by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize().safeDrawingPadding()) { App() }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
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

        NavHost(nav, startDestination = if (status.complete) Routes.SETTINGS else Routes.ONBOARDING) {
            composable(Routes.ONBOARDING) {
                LaunchedEffect(status.complete) {
                    if (status.complete) nav.navigate(Routes.SETTINGS) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
                }
                OnboardingScreen(
                    status = status,
                    onEnableKeyboard = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                    onRequestMic = { micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    onConnect = { SignInLauncher.start(this@MainActivity) },
                    onPasteKey = { graph.apiKeyStore.set(it) },
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    settings = settings,
                    keyConnected = key != null,
                    onConnect = { SignInLauncher.start(this@MainActivity) },
                    onPasteKey = { graph.apiKeyStore.set(it) },
                    onDisconnect = { graph.apiKeyStore.clear() },
                    onChange = { transform -> scope.launch { graph.settings.update(transform) } },
                    onOpen = { nav.navigate(it) },
                )
            }
            composable(Routes.CATEGORIES) { CategoriesScreen() }
            composable(Routes.MODELS) { ModelsScreen() }
            composable(Routes.HISTORY) { HistoryScreen() }
        }
    }
}
