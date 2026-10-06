package io.github.agopalareddy.umm.desktop

import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import androidx.navigation.compose.rememberNavController
import io.github.agopalareddy.umm.core.platform.XdgPaths
import io.github.agopalareddy.umm.settings.HomeSetup
import io.github.agopalareddy.umm.settings.Routes
import io.github.agopalareddy.umm.settings.UmmNavHost
import io.github.agopalareddy.umm.ui.LocalUmm
import io.github.agopalareddy.umm.ui.UmmTheme
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

private const val OPENROUTER_KEYS_URL = "https://openrouter.ai/settings/keys"

fun main(args: Array<String>) {
    if (args.isNotEmpty()) {
        val dictateArgs = parseArgs(args)
        if (dictateArgs == null) {
            System.err.println(USAGE)
            exitProcess(2)
        }
        val graph = DesktopGraph(XdgPaths(), System.getenv())
        val code = runBlocking { dictate(dictateArgs, graph, System.out) }
        graph.close()
        exitProcess(code)
    }

    val graph = DesktopGraph(XdgPaths(), System.getenv())
    application {
        Window(
            onCloseRequest = { graph.close(); exitApplication() },
            title = "Umm",
            state = rememberWindowState(width = 420.dp, height = 860.dp),
        ) {
            val snackbar = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val services = remember { graph.services(DesktopPlatform(snackbar, scope)) }
            val key by graph.apiKeyStore.key.collectAsState()
            val nav = rememberNavController()
            CompositionLocalProvider(LocalUmm provides services) {
                UmmTheme {
                    // Every page draws its own Scaffold and bars; this one only hosts the snackbar.
                    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { _ ->
                        UmmNavHost(
                            startRoute = Routes.HOME,
                            // Without OPENROUTER_API_KEY, Home's setup prompt leads to the Account page.
                            home = HomeSetup(complete = key != null, onSetup = { nav.navigate(Routes.ACCOUNT) }, switchKeyboard = null),
                            onConnect = { services.platform.openUrl(OPENROUTER_KEYS_URL) },
                            nav = nav,
                        )
                    }
                }
            }
        }
    }
}
