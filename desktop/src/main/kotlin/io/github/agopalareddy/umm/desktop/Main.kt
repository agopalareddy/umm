package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.platform.XdgPaths
import io.github.agopalareddy.umm.linux.SecretServiceStore
import io.github.agopalareddy.umm.linux.SingleInstance
import io.github.agopalareddy.umm.linux.portal.Portal
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) {
    if ("--dictate" in args) {
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
    if ("--type-test" in args) {
        val typeArgs = parseTypeTestArgs(args)
        if (typeArgs == null) {
            System.err.println(TYPE_TEST_USAGE)
            exitProcess(2)
        }
        exitProcess(typeTest(typeArgs, System.out))
    }
    if (args.any { it.startsWith("--") && it != "--background" }) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    runApp(args)
}

private fun runApp(args: Array<String>) {
    val portal = try {
        Portal.connect(DesktopEngine.APP_ID)
    } catch (e: Exception) {
        System.err.println("Umm needs a D-Bus session bus: ${e.message}")
        exitProcess(1)
    }
    portal.registrationError?.let { System.err.println("Portal app registration: $it") }

    val engine = AtomicReference<DesktopEngine?>()
    val single = SingleInstance(portal)
    val owner = single.claim(
        onActivate = { engine.get()?.onOpenWindow?.invoke() },
        onUrl = { engine.get()?.onLink(it) },
    )
    if (!owner) {
        // Another Umm is running: hand over what this launch was for (a window, or a sign-in link) and leave.
        if ("--background" !in args) runCatching { single.forward(args) }.onFailure { System.err.println("Umm is already running.") }
        portal.close()
        exitProcess(0)
    }

    val secretStore = SecretServiceStore.openOrNull(portal)
    val graph = DesktopGraph(XdgPaths(), System.getenv(), secretStore = secretStore)
    val app = DesktopEngine(portal, graph, secretStore)
    engine.set(app)
    app.start()
    args.firstOrNull { it.startsWith("umm://") }?.let(app::onLink)
    runDesktopApp(app, startVisible = "--background" !in args)
}
