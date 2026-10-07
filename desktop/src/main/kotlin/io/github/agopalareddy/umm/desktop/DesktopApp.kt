package io.github.agopalareddy.umm.desktop

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.agopalareddy.umm.settings.HomeSetup
import io.github.agopalareddy.umm.settings.Routes
import io.github.agopalareddy.umm.settings.SettingsEntry
import io.github.agopalareddy.umm.settings.UmmNavHost
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.ui.UmmTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * The Compose side of the app: the orb, and the settings window, which is hidden rather than closed so the app
 * keeps listening for the hotkey. Quitting is from the tray.
 */
fun runDesktopApp(engine: DesktopEngine, startVisible: Boolean) = application {
    var windowVisible by remember { mutableStateOf(startVisible) }
    // Bumped on every request to show the window, so a second launch also raises one that is open but buried.
    var raise by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        engine.onOpenWindow = {
            windowVisible = true
            raise++
        }
        engine.onQuit = {
            engine.close()
            exitApplication()
        }
    }

    val prefs by engine.graph.desktopSettings.prefs.collectAsState(DesktopPrefs())
    val dictation by engine.controller.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val services = remember { engine.graph.services(DesktopPlatform(snackbar, scope)) }

    // Both windows are themed from the shared settings, so both need the services.
    CompositionLocalProvider(LocalUmm provides services) {
        OrbWindow(
            state = dictation,
            position = prefs.orbPosition,
            onStop = engine.controller::stop,
            onCancel = engine.controller::cancel,
            onOpen = { engine.onOpenWindow() },
        )

        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = "Umm",
            state = rememberWindowState(width = 420.dp, height = 860.dp),
        ) {
            LaunchedEffect(windowVisible, raise) {
                if (windowVisible) window.toFront()
            }
            val key by engine.graph.apiKeyStore.key.collectAsState()
            val nav = rememberNavController()
            // First launch opens setup; read once so the screen doesn't flip when the settings load.
            val startRoute = remember { if (runBlocking { engine.graph.desktopSettings.prefs.first().setupDone }) Routes.HOME else DesktopRoutes.SETUP }
            UmmTheme {
                // Every page draws its own Scaffold and bars; this one only hosts the snackbar.
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
                    UmmNavHost(
                        startRoute = startRoute,
                        home = HomeSetup(
                            complete = key != null && prefs.setupDone,
                            onSetup = { nav.navigate(DesktopRoutes.SETUP) },
                            switchKeyboard = null,
                        ),
                        onConnect = engine.signIn::start,
                        extraSettings = listOf(
                            SettingsEntry(Icons.Rounded.Computer, "Desktop", "Shortcut, orb, startup, microphone", DesktopRoutes.SETTINGS),
                        ),
                        nav = nav,
                        platformRoutes = { controller ->
                            composable(DesktopRoutes.SETUP) {
                                SetupScreen(engine) {
                                    controller.navigate(Routes.HOME) { popUpTo(controller.graph.startDestinationId) { inclusive = true } }
                                }
                            }
                            composable(DesktopRoutes.SETTINGS) {
                                DesktopSettingsPage(engine, onBack = { controller.popBackStack() }, onSetup = { controller.navigate(DesktopRoutes.SETUP) })
                            }
                        },
                    )
                }
            }
        }
    }
}
