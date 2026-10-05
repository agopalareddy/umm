package io.github.agopalareddy.umm.core.platform

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class XdgPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun defaults_whenVariablesUnset() {
        val paths = XdgPaths(env = emptyMap(), home = tmp.root)
        assertEquals(File(tmp.root, ".local/share/umm"), paths.dataDir)
        assertEquals(File(tmp.root, ".config/umm"), paths.configDir)
        assertTrue(paths.dataDir.isDirectory)
        assertTrue(paths.configDir.isDirectory)
    }

    @Test fun honoursXdgVariables() {
        val env = mapOf("XDG_DATA_HOME" to "${tmp.root}/d", "XDG_CONFIG_HOME" to "${tmp.root}/c")
        val paths = XdgPaths(env = env, home = tmp.root)
        assertEquals(File(tmp.root, "d/umm"), paths.dataDir)
        assertEquals(File(tmp.root, "c/umm"), paths.configDir)
        assertTrue(paths.dataDir.isDirectory)
    }

    /** The XDG spec says empty or relative values are invalid and must be ignored. */
    @Test fun ignoresEmptyAndRelativeValues() {
        val paths = XdgPaths(env = mapOf("XDG_DATA_HOME" to "", "XDG_CONFIG_HOME" to "rel/dir"), home = tmp.root)
        assertEquals(File(tmp.root, ".local/share/umm"), paths.dataDir)
        assertEquals(File(tmp.root, ".config/umm"), paths.configDir)
    }
}
