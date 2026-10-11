package io.github.agopalareddy.umm.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.time.LocalDateTime

class SyncLogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val noon = LocalDateTime.of(2026, 10, 11, 12, 0, 5)

    @Test
    fun writesOneStampedLinePerEventForTheOwnerOnly() {
        val file = File(tmp.root, "state/sync.log")
        val log = FileSyncLog(file, now = { noon })

        log.log("sync: lan | wifi: Pixel sent=1 received=0 | relays: off or none")
        log.log("relay nos.lol: published seq 4-6")

        assertEquals(
            listOf(
                "2026-10-11 12:00:05 sync: lan | wifi: Pixel sent=1 received=0 | relays: off or none",
                "2026-10-11 12:00:05 relay nos.lol: published seq 4-6",
            ),
            file.readLines(),
        )
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(file.toPath()),
        )
    }

    @Test
    fun keepsOneOlderFileWhenFull() {
        val file = File(tmp.root, "sync.log")
        val log = FileSyncLog(file, maxBytes = 200, now = { noon })

        repeat(20) { log.log("event number $it") }

        assertTrue(file.length() <= 200)
        assertTrue(File(file.path + ".1").exists())
        assertEquals("2026-10-11 12:00:05 event number 19", file.readLines().last())
        assertEquals(listOf("sync.log", "sync.log.1"), tmp.root.list()!!.sorted())
    }

    @Test
    fun errorTextCannotCarryAnAddressOrAKey() {
        val file = File(tmp.root, "sync.log")
        val log = FileSyncLog(file, now = { noon })
        val key = "3f9a1c0de4b7a2915c6d8e0f1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d"

        log.log("Pixel unreachable (failed to connect to /192.168.1.23:41733 from fe80:0:0:0:1c2d:3e4f:5a6b:7c8d)")
        log.log("relay nos.lol: publish failed: Relay rejected event $key: rate-limited")
        log.log("one line\nsplit in two " + "x".repeat(500))

        val text = file.readText()
        assertFalse(text.contains("192.168.1.23"))
        assertFalse(text.contains("1c2d:3e4f"))
        assertFalse(text.contains(key))
        assertTrue(text.contains("3f9a1c0d…"))
        assertEquals(3, file.readLines().size)
        assertTrue(file.readLines().all { it.length <= 300 })
    }
}
