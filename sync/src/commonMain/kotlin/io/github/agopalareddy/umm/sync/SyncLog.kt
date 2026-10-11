package io.github.agopalareddy.umm.sync

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A local record of what sync did, for working out why a change did not arrive. Lines carry device ids, counts,
 * sequence numbers, relay hosts and error text only: never dictation text, setting values, keys or pairing codes.
 */
fun interface SyncLog {
    fun log(line: String)

    companion object {
        val None = SyncLog { }
    }
}

/** Appends to [file], readable by its owner only, and keeps one older file once [maxBytes] is reached. */
class FileSyncLog(
    private val file: File,
    private val maxBytes: Long = 512 * 1024L,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val alsoTo: (String) -> Unit = { },
) : SyncLog {

    @Synchronized
    override fun log(line: String) {
        val text = "${now().format(STAMP)} ${scrub(line).take(MAX_LINE)}"
        alsoTo(text)
        runCatching {
            if (!file.exists()) {
                file.parentFile?.mkdirs()
                file.createNewFile()
                file.setReadable(false, false)
                file.setWritable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            } else if (file.length() + text.length > maxBytes) {
                val older = File(file.path + ".1")
                older.delete()
                file.renameTo(older)
                return log(line)
            }
            file.appendText(text + "\n")
        }
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        const val MAX_LINE = 280
    }
}

private val ADDRESS = Regex("""\b\d{1,3}(\.\d{1,3}){3}\b|\b(?:[0-9a-fA-F]{1,4}:){3,7}[0-9a-fA-F]{0,4}\b""")
private val LONG_HEX = Regex("""\b[0-9a-fA-F]{16,}\b""")

/** Error text can quote an address or a key; neither belongs in the log. */
internal fun scrub(line: String): String =
    line.replace(ADDRESS, "<address>").replace(LONG_HEX) { it.value.take(8) + "…" }.replace('\n', ' ')

internal fun relayHost(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':')
