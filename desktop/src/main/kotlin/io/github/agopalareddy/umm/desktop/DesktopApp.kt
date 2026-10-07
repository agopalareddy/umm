package io.github.agopalareddy.umm.desktop

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import io.github.agopalareddy.umm.settings.HomeSetup
import io.github.agopalareddy.umm.settings.Routes
import io.github.agopalareddy.umm.settings.UmmNavHost
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.ui.UmmTheme

/**
 * The Compose side of the app: the orb, and the settings window, which is hidden rather than closed so the app
 * keeps listening for the hotkey. Quitting is from the tray.
 */
fun runDesktopApp(engine: DesktopEngine, startVisible: Boolean) = application {
    var windowVisible by remember { mutableStateOf(startVisible) }
    LaunchedEffect(Unit) {
        engine.onOpenWindow = { windowVisible = true }
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
            onOpen = { windowVisible = true },
        )

        Window(
            onCloseRequest = { windowVisible = false },
            visible = windowVisible,
            title = "Umm",
            state = rememberWindowState(width = 420.dp, height = 860.dp),
        ) {
            LaunchedEffect(windowVisible) {
                if (windowVisible) window.toFront()
            }
            val key by engine.graph.apiKeyStore.key.collectAsState()
            val nav = rememberNavController()
            UmmTheme {
                // Every page draws its own Scaffold and bars; this one only hosts the snackbar.
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
                    UmmNavHost(
                        startRoute = Routes.HOME,
                        home = HomeSetup(complete = key != null, onSetup = { nav.navigate(Routes.ACCOUNT) }, switchKeyboard = null),
                        onConnect = engine.signIn::start,
                        nav = nav,
                    )
                }
            }
        }
    }
}
