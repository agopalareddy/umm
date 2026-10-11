package io.github.agopalareddy.umm.core.cleanup

import kotlinx.serialization.Serializable

@Serializable
enum class CleanupLevel { RAW, LIGHT, FORMATTED, POLISHED }

