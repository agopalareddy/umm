package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.CategoryConfig

data class ResolvedStyle(val level: CleanupLevel, val script: ScriptPreference)

object LevelResolver {
    /** Precedence: one-off override, then the category's level, then the global default. Script always comes from the category. */
    fun resolve(override: CleanupLevel?, category: CategoryConfig, globalDefault: CleanupLevel): ResolvedStyle =
        ResolvedStyle(override ?: category.level ?: globalDefault, category.script)
}
