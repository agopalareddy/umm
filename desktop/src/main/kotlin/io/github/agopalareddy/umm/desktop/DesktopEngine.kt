package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.core.auth.KeyValueStore
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.pipeline.DictationRequest
import io.github.agopalareddy.umm.core.policy.LevelResolver
import io.github.agopalareddy.umm.desktop.engine.DesktopState
import io.github.agopalareddy.umm.desktop.engine.DictationController
import io.github.agopalareddy.umm.linux.Autostart
import io.github.agopalareddy.umm.linux.AwtClipboard
import io.github.agopalareddy.umm.linux.BindResult
import io.github.agopalareddy.umm.linux.DbusNotifier
import io.github.agopalareddy.umm.linux.FocusTracker
import io.github.agopalareddy.umm.linux.GlobalShortcutsHotkey
import io.github.agopalareddy.umm.linux.JavaSoundMicrophone
import io.github.agopalareddy.umm.linux.KWinFocusTracker
import io.github.agopalareddy.umm.linux.PortalTextInserter
import io.github.agopalareddy.umm.linux.SniTrayIcon
import io.github.agopalareddy.umm.linux.TrayAction
import io.github.agopalareddy.umm.linux.TrayState
import io.github.agopalareddy.umm.linux.capabilityFor
import io.github.agopalareddy.umm.linux.portal.Portal
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Everything that runs behind the window: hotkey, controller, tray, sounds and sign-in. */
class DesktopEngine(
    val portal: Portal,
    val graph: DesktopGraph,
    secretStore: KeyValueStore?,
    env: Map<String, String> = System.getenv(),
) {
    /** Set by the app: show the settings window. */
    @Volatile var onOpenWindow: () -> Unit = {}

    /** Set by the app: shut everything down and exit. */
    @Volatile var onQuit: () -> Unit = {}

    @Volatile private var prefs = DesktopPrefs()

    val hotkey = GlobalShortcutsHotkey(portal)
    val notifier = DbusNotifier(portal)
    private val tray = SniTrayIcon(portal)
    private val sounds = Sounds { prefs.sounds }
    private val microphone = JavaSoundMicrophone { prefs.microphone }
    private val focus: FocusTracker = KWinFocusTracker.createOrNull(portal, env) ?: object : FocusTracker {
        override fun focusedAppId(): String? = null
    }

    private val _bindResult = MutableStateFlow<BindResult?>(null)

    /** How binding the shortcut went; null until the first attempt finishes. */
    val bindResult: StateFlow<BindResult?> = _bindResult.asStateFlow()

    val signIn = SignIn(graph.openRouter, graph.apiKeyStore, openUrl = ::openInBrowser, keyLabel = ::keyLabel)

    val inserter = PortalTextInserter(portal, secretStore ?: InMemoryKeyValueStore(), capabilityFor(env))

    private val autostart = Autostart.forEnvironment(env, File(System.getProperty("user.home")))

    /** The installed launcher's path; null when running from Gradle, where there is nothing to start at login. */
    val launcher: String? = System.getProperty("jpackage.app-path")

    val controller = DictationController(
        scope = graph.scope,
        hotkey = hotkey,
        pipeline = graph.pipeline(microphone),
        inserter = inserter,
        clipboard = AwtClipboard(),
        notifier = notifier,
        focus = focus,
        hasKey = { graph.apiKeyStore.get() != null },
        micAvailable = { microphone.available() },
        request = ::request,
        onOpenApp = { onOpenWindow() },
    )

    init {
        portal.register(APP_ID)
        graph.scope.launch { graph.desktopSettings.prefs.collect { prefs = it } }
    }

    /** Shows the tray icon, binds the shortcut, and starts mirroring the dictation state to the tray and sounds. */
    fun start() {
        tray.show(TrayState.IDLE) { action ->
            when (action) {
                TrayAction.OPEN -> onOpenWindow()
                TrayAction.START -> controller.start()
                TrayAction.STOP -> controller.stop()
                TrayAction.CANCEL -> controller.cancel()
                TrayAction.QUIT -> onQuit()
            }
        }
        graph.scope.launch {
            var wasListening = false
            controller.state.collect { state ->
                tray.update(
                    when (state) {
                        is DesktopState.Listening -> TrayState.RECORDING
                        DesktopState.Processing -> TrayState.BUSY
                        else -> TrayState.IDLE
                    },
                )
                val listening = state is DesktopState.Listening
                if (listening && !wasListening) sounds.start()
                if (!listening && wasListening) sounds.stop()
                wasListening = listening
            }
        }
        bindHotkey()
    }

    fun bindHotkey() {
        graph.scope.launch { _bindResult.value = hotkey.bind() }
    }

    /** Turns "start at login" on or off (the autostart entry, and the setting that shows it). */
    suspend fun setStartAtLogin(on: Boolean) {
        launcher?.let { if (on) autostart.enable(it) else autostart.disable() }
        graph.desktopSettings.update { it.copy(startAtLogin = on && launcher != null) }
    }

    /** A `umm://` link: finishes a pending sign-in and brings the window forward. */
    fun onLink(url: String) {
        graph.scope.launch {
            if (signIn.finish(url)) {
                notifier.notify("Signed in", "Umm is connected to OpenRouter.")
            } else {
                notifier.notify("Couldn't sign in", "The sign-in expired or was cancelled. Connect again from Umm.")
            }
            onOpenWindow()
        }
    }

    fun close() {
        tray.hide()
        (focus as? KWinFocusTracker)?.close()
        graph.close()
        portal.close()
    }

    private suspend fun request(packageName: String): DictationRequest {
        val settings = graph.settings.settings.first()
        val style = LevelResolver.resolve(null, graph.categories.configFor(packageName), settings.defaultLevel)
        return DictationRequest(
            packageName = packageName,
            level = style.level,
            script = style.script,
            language = LanguageChoice.decode(settings.defaultLanguage),
            silenceTimeoutSec = settings.silenceTimeoutSec,
        )
    }

    private fun openInBrowser(url: String) {
        runCatching { ProcessBuilder("xdg-open", url).start() }
    }

    /** Each sign-in makes a key on OpenRouter, so name it clearly enough to find and delete later. */
    private fun keyLabel(): String {
        val host = runCatching { File("/etc/hostname").readText().trim() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: "Linux"
        return "Umm · $host · ${LocalDate.now()}"
    }

    companion object {
        const val APP_ID = "io.github.agopalareddy.Umm"
    }
}
