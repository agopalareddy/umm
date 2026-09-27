package io.github.agopalareddy.umm

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DebugKeyTest {
    @Test
    fun debugKeyIsInjectedWhenEnvPresent() {
        val env = File("../.env")
        assumeTrue(env.exists())
        assertTrue(BuildConfig.DEBUG_OPENROUTER_API_KEY.startsWith("sk-or-"))
    }
}
