package io.github.agopalareddy.umm.ime

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.CategoryConfig
import io.github.agopalareddy.umm.core.pipeline.DictationRequest
import io.github.agopalareddy.umm.core.pipeline.DictationState
import io.github.agopalareddy.umm.core.policy.LevelResolver
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The voice-only keyboard: starts listening when shown and inserts the cleaned text into the focused field. */
class UmmInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val pipeline by lazy { graph.pipeline }

    private var session = 0
    private var dictationSession = 0
    private var inputActive = false
    private var dictationLevel: CleanupLevel? = null
    private var multiLine = false

    /** What the panel shows; read by [KeyboardPanel]. */
    internal var ui by mutableStateOf(PanelContext())
        private set

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleScope.launch { pipeline.state.collect(::onState) }
    }

    override fun onCreateInputView(): View {
        window.window?.decorView?.let {
            it.setViewTreeLifecycleOwner(this)
            it.setViewTreeSavedStateRegistryOwner(this)
        }
        return ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@UmmInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@UmmInputMethodService)
            setContent { KeyboardPanel(this@UmmInputMethodService) }
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        session++
        inputActive = true
        multiLine = FieldPolicy.isMultiLine(info.inputType)
        val packageName = info.packageName ?: ""
        val setupDone = hasMicPermission() && graph.apiKeyStore.get() != null
        val password = FieldPolicy.isPassword(info.inputType)
        ui = PanelContext(packageName = packageName, appLabel = appLabel(packageName), setupDone = setupDone, password = password)
        if (!setupDone || password) return
        lifecycleScope.launch {
            val settings = graph.settings.settings.first()
            val config = graph.categories.configFor(packageName)
            val language = LanguageChoice.decode(settings.defaultLanguage)
            ui = ui.copy(category = config, language = language, languages = settings.keyboardLanguages)
            val state = pipeline.state.value
            if (state is DictationState.Idle || state.isFinished()) {
                pipeline.reset()
                startDictation()
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputActive = false
        if (pipeline.state.value is DictationState.Listening) pipeline.stop()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    // --- Actions from the panel ---

    internal fun onMicTapped() {
        when (val state = pipeline.state.value) {
            is DictationState.Listening -> pipeline.stop()
            is DictationState.Failed -> { pipeline.reset(); dictationSession = session; pipeline.retry(state.historyId) }
            DictationState.Transcribing, DictationState.Cleaning -> Unit
            else -> { pipeline.reset(); startDictation() }
        }
    }

    internal fun onLevelChosen(level: CleanupLevel) {
        ui = ui.copy(levelOverride = level, level = level)
        dictationLevel = level
    }

    internal fun onLanguageChosen(code: String) {
        val language = LanguageChoice.decode(code)
        ui = ui.copy(language = language)
        // Language affects transcription, so restart if nothing has been said yet.
        val state = pipeline.state.value
        if (state is DictationState.Listening && !state.speechDetected) {
            pipeline.cancel()
            lifecycleScope.launch {
                pipeline.state.first { it is DictationState.Idle }
                startDictation()
            }
        }
    }

    internal fun onCategoryChosen(category: Category) {
        val packageName = ui.packageName
        lifecycleScope.launch {
            graph.categories.assign(packageName, category)
            val config = graph.categories.configFor(packageName)
            ui = ui.copy(category = config)
            if (ui.levelOverride == null) {
                val level = resolvedLevel(config)
                dictationLevel = level
                ui = ui.copy(level = level)
            }
        }
    }

    internal fun onSwitchKeyboard() {
        if (pipeline.state.value is DictationState.Listening) pipeline.cancel()
        switchToPreviousInputMethod()
    }

    internal fun openApp(uri: Uri? = null) {
        val intent = if (uri != null) Intent(Intent.ACTION_VIEW, uri) else Intent(this, MainActivity::class.java)
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal val state get() = pipeline.state

    // --- Pipeline ---

    private fun startDictation() {
        val config = ui.category ?: return
        lifecycleScope.launch {
            val settings = graph.settings.settings.first()
            val style = LevelResolver.resolve(ui.levelOverride, config, settings.defaultLevel)
            dictationLevel = style.level
            ui = ui.copy(level = style.level)
            dictationSession = session
            pipeline.start(
                DictationRequest(ui.packageName, style.level, style.script, ui.language, settings.silenceTimeoutSec),
            )
        }
    }

    private suspend fun resolvedLevel(config: CategoryConfig): CleanupLevel =
        LevelResolver.resolve(null, config, graph.settings.settings.first().defaultLevel).level

    private suspend fun onState(state: DictationState) {
        if (state !is DictationState.Done) return
        var text = state.text
        // The level changed after recording started: re-run cleanup on the stored transcript.
        val wanted = dictationLevel
        val used = graph.history.get(state.historyId)?.level
        if (!state.cleanupFailed && wanted != null && used != null && wanted != used) {
            text = runCatching { pipeline.reclean(state.historyId, wanted, ui.category?.script ?: return@runCatching text) }
                .getOrDefault(text)
        }
        deliver(text)
        pipeline.reset()
        if (state.cleanupFailed) Toast.makeText(this, "Cleanup failed; inserted the raw transcript", Toast.LENGTH_SHORT).show()
        if (inputActive && graph.settings.settings.first().switchBackAfterInsert) switchToPreviousInputMethod()
    }

    private suspend fun deliver(text: String) = withContext(Dispatchers.Main) {
        val connection = currentInputConnection
        when (InsertionDecider.decide(dictationSession, session, inputActive && connection != null)) {
            Insertion.Commit -> {
                val before = connection.getTextBeforeCursor(1, 0)
                connection.commitText(TextInsertion.prepare(text, before, multiLine), 1)
            }
            Insertion.Clipboard -> {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Umm", text))
                Toast.makeText(this@UmmInputMethodService, "Copied — field changed", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun hasMicPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun appLabel(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrDefault(packageName)
}

internal data class PanelContext(
    val packageName: String = "",
    val appLabel: String = "",
    val setupDone: Boolean = true,
    val password: Boolean = false,
    val category: CategoryConfig? = null,
    val levelOverride: CleanupLevel? = null,
    /** The level this dictation will be cleaned at. */
    val level: CleanupLevel? = null,
    val language: LanguageChoice = LanguageChoice.Auto,
    val languages: List<String> = listOf("auto", "en"),
)

internal fun DictationState.isFinished() = this is DictationState.Done || this is DictationState.Failed ||
    this == DictationState.NoSpeech || this == DictationState.EmptyTranscript
