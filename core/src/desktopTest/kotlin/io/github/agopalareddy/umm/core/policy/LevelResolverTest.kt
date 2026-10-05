package io.github.agopalareddy.umm.core.policy

import io.github.agopalareddy.umm.core.cleanup.CleanupLevel
import io.github.agopalareddy.umm.core.cleanup.ScriptPreference
import io.github.agopalareddy.umm.core.data.Category
import io.github.agopalareddy.umm.core.data.CategoryConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class LevelResolverTest {
    private val email = CategoryConfig(Category.EMAIL, CleanupLevel.FORMATTED, ScriptPreference.NATIVE)
    private val other = CategoryConfig(Category.OTHER, null, ScriptPreference.LATIN)

    @Test fun overrideWins() {
        assertEquals(
            ResolvedStyle(CleanupLevel.RAW, ScriptPreference.NATIVE),
            LevelResolver.resolve(CleanupLevel.RAW, email, CleanupLevel.LIGHT),
        )
    }

    @Test fun categoryBeatsDefault() {
        assertEquals(
            ResolvedStyle(CleanupLevel.FORMATTED, ScriptPreference.NATIVE),
            LevelResolver.resolve(null, email, CleanupLevel.LIGHT),
        )
    }

    @Test fun otherUsesGlobalDefault() {
        assertEquals(
            ResolvedStyle(CleanupLevel.POLISHED, ScriptPreference.LATIN),
            LevelResolver.resolve(null, other, CleanupLevel.POLISHED),
        )
    }
}
