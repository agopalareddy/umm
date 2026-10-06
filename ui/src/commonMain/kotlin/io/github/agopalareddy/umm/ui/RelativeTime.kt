package io.github.agopalareddy.umm.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Just now", "5 minutes ago", "3 hours ago", "Yesterday", "4 days ago", then the date ("Sep 28", or "Sep 28, 2025"
 * in an earlier year). Days count elapsed 24-hour periods, so the result does not depend on the time zone until the
 * date is shown.
 */
fun relativeTime(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val elapsed = (nowMs - thenMs).coerceAtLeast(0)
    val minutes = elapsed / 60_000
    val hours = elapsed / 3_600_000
    val days = elapsed / 86_400_000
    return when {
        minutes < 1 -> "Just now"
        hours < 1 -> plural(minutes, "minute")
        days < 1 -> plural(hours, "hour")
        days == 1L -> "Yesterday"
        days <= 7 -> "$days days ago"
        else -> {
            val then = Instant.ofEpochMilli(thenMs).atZone(zone)
            val pattern = if (then.year == Instant.ofEpochMilli(nowMs).atZone(zone).year) "MMM d" else "MMM d, yyyy"
            then.format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
        }
    }
}

private fun plural(count: Long, unit: String) = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"
