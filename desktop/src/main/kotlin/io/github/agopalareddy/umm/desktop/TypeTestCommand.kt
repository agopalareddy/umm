package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.desktop.engine.InsertionPlanner
import io.github.agopalareddy.umm.linux.PortalTextInserter
import io.github.agopalareddy.umm.linux.SecretServiceStore
import io.github.agopalareddy.umm.linux.capabilityFor
import io.github.agopalareddy.umm.linux.portal.Portal
import java.io.PrintStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

data class TypeTestArgs(val text: String, val delaySeconds: Int)

const val TYPE_TEST_USAGE = "Usage: umm --type-test <text> [--delay <seconds>]"

/** Parses `--type-test <text> [--delay <seconds>]`; null when absent or malformed. */
fun parseTypeTestArgs(args: Array<String>): TypeTestArgs? {
    val flag = args.indexOf("--type-test")
    if (flag < 0) return null
    val text = args.getOrNull(flag + 1)?.takeUnless { it.startsWith("--") } ?: return null
    val delay = if ("--delay" in args) args.getOrNull(args.indexOf("--delay") + 1)?.toIntOrNull() ?: return null else 8
    return TypeTestArgs(text, delay)
}

/**
 * Developer check of the insertion path with no microphone: after a delay (to focus a text field), types and pastes
 * [TypeTestArgs.text] the way a dictation would on this desktop. Returns the exit code.
 */
fun typeTest(args: TypeTestArgs, out: PrintStream): Int {
    val portal = try {
        Portal.connect(DesktopEngine.APP_ID)
    } catch (e: Exception) {
        out.println("No D-Bus session bus: ${e.message}")
        return 2
    }
    portal.use {
        val secrets = SecretServiceStore.openOrNull(it)
        out.println(if (secrets != null) "Keyring: found" else "Keyring: none (restore token kept in memory)")
        val inserter = PortalTextInserter(it, secrets ?: InMemoryKeyValueStore(), capabilityFor(System.getenv()))
        out.println("Focus the target field: typing in ${args.delaySeconds} s (${inserter.capability})")
        return runBlocking {
            delay(args.delaySeconds * 1000L)
            val parts = InsertionPlanner.plan(args.text, inserter.capability)
            out.println("Parts: $parts")
            try {
                inserter.insert(parts)
                out.println("Inserted.")
                0
            } catch (e: Exception) {
                out.println("Failed: ${e.message}")
                1
            }
        }
    }
}
