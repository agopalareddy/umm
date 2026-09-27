package io.github.agopalareddy.umm.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.view.View
import android.view.inputmethod.EditorInfo
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
import io.github.agopalareddy.umm.core.pipeline.isFinished
import io.github.agopalareddy.umm.core.policy.LevelResolver
import io.github.agopalareddy.umm.graph
import io.github.agopalareddy.umm.settings.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The voice-only keyboard: starts listening when shown; results are delivered by the app's DeliveryRouter. */
class UmmInputMethodService : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val pipeline by lazy { graph.pipeline }
    private val delivery by lazy { graph.delivery }
    private var target: FieldTarget? = null

    /** What the panel shows; read by [KeyboardPanel]. */
    internal var ui by mutableStateOf(PanelContext())
        private set

    internal val state get() = pipeline.state

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_CREATE)
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
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_RESUME)
        val field = FieldTarget(delivery.newOrigin(), FieldPolicy.isMultiLine(info.inputType))
        target?.let(delivery::detach)
        target = field
        delivery.attach(field)

        val packageName = info.packageName ?: ""
        val setupDone = hasMicPermission() && graph.apiKeyStore.get() != null
        val password = FieldPolicy.isPassword(info.inputType)
        ui = PanelContext(packageName = packageName, appLabel = appLabel(packageName), setupDone = setupDone, password = password)
        if (!setupDone || password) return
        lifecycleScope.launch {
            val settings = graph.settings.settings.first()
            val config = graph.categories.configFor(packageName)
            if (!delivery.isCurrent(field.origin)) return@launch // the keyboard moved on while we were reading
            ui = ui.copy(
                category = config,
                language = LanguageChoice.decode(settings.defaultLanguage),
                languages = settings.keyboardLanguages,
            )
            val current = pipeline.state.value
            if (current is DictationState.Idle || current.isFinished()) {
                pipeline.reset()
                startDictation(field.origin)
            }
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        target?.let(delivery::detach)
        when (KeyboardHidden.action(pipeline.state.value)) {
            HideAction.CANCEL -> pipeline.cancel()
            HideAction.STOP -> pipeline.stop()
            HideAction.NONE -> Unit
        }
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_PAUSE)
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        target?.let(delivery::detach)
        super.onDestroy() // calls onFinishInputView, so the lifecycle must still be alive
        lifecycleRegistry.moveIfAlive(Lifecycle.Event.ON_DESTROY)
    }

    // --- Actions from the panel ---

    internal fun onMicTapped() {
        val origin = target?.origin ?: return
        when (val state = pipeline.state.value) {
            is DictationState.Listening -> pipeline.stop()
            is DictationState.Failed -> { pipeline.reset(); pipeline.retry(state.historyId, origin) }
            DictationState.Transcribing, DictationState.Cleaning -> Unit
            else -> { pipeline.reset(); startDictation(origin) }
        }
    }

    /** Double-tap: keep recording through silence until the user taps Finish. */
    internal fun onMicDoubleTapped() {
        val origin = target?.origin ?: return
        when (val state = pipeline.state.value) {
            is DictationState.Listening -> pipeline.setContinuous()
            DictationState.Transcribing, DictationState.Cleaning -> Unit
            is DictationState.Failed -> onMicTapped()
            else -> { pipeline.reset(); startDictation(origin, continuous = true) }
        }
    }

    internal fun onLevelChosen(level: CleanupLevel) {
        ui = ui.copy(levelOverride = level, level = level)
        pipeline.setLevel(level)
    }

    internal fun onLanguageChosen(code: String) {
        ui = ui.copy(language = LanguageChoice.decode(code))
        // Language affects transcription, so restart if nothing has been said yet.
        val state = pipeline.state.value
        val origin = target?.origin ?: return
        if (state is DictationState.Listening && !state.speechDetected) {
            pipeline.cancel()
            lifecycleScope.launch {
                pipeline.state.first { it is DictationState.Idle }
                startDictation(origin)
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
                val level = resolve(null, config)
                ui = ui.copy(level = level)
                pipeline.setLevel(level)
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

    // --- Pipeline ---

    private fun startDictation(origin: Long, continuous: Boolean = false) {
        val config = ui.category ?: return
        lifecycleScope.launch {
            val settings = graph.settings.settings.first()
            val level = resolve(ui.levelOverride, config)
            if (!delivery.isCurrent(origin)) return@launch
            ui = ui.copy(level = level)
            pipeline.start(
                DictationRequest(ui.packageName, level, config.script, ui.language, settings.silenceTimeoutSec, origin, continuous),
            )
        }
    }

    private suspend fun resolve(override: CleanupLevel?, config: CategoryConfig): CleanupLevel =
        LevelResolver.resolve(override, config, graph.settings.settings.first().defaultLevel).level

    private inner class FieldTarget(override val origin: Long, private val multiLine: Boolean) : InsertionTarget {
        override fun commit(text: String): Boolean {
            val connection = currentInputConnection ?: return false
            val before = connection.getTextBeforeCursor(1, 0)
            val after = connection.getTextAfterCursor(1, 0)
            return connection.commitText(TextInsertion.prepare(text, before, after, multiLine), 1)
        }

        override fun onInserted() {
            ui = ui.copy(levelOverride = null)
            lifecycleScope.launch {
                if (graph.settings.settings.first().switchBackAfterInsert) switchToPreviousInputMethod()
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
    /** A one-off level for this dictation only; cleared after it is inserted and for each new field. */
    val levelOverride: CleanupLevel? = null,
    /** The level this dictation will be cleaned at. */
    val level: CleanupLevel? = null,
    val language: LanguageChoice = LanguageChoice.Auto,
    val languages: List<String> = listOf("auto", "en"),
)
