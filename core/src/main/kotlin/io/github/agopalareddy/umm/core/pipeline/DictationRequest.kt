package io.github.agopalareddy.umm.core.pipeline

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.LanguageChoice
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference

data class DictationRequest(
    val packageName: String,
    val level: CleanupLevel,
    val script: ScriptPreference,
    val language: LanguageChoice,
    /** null means Off. */
    val silenceTimeoutSec: Int?,
    /** Opaque tag chosen by the front-end (e.g. its input session), echoed on the result. */
    val origin: Long = 0,
)
