package io.github.agopalareddy.umm.core.cleanup

import kotlinx.serialization.Serializable

/** How mixed-language speech is written: all in Latin letters, or each language in its own script. */
@Serializable
enum class ScriptPreference { LATIN, NATIVE }

