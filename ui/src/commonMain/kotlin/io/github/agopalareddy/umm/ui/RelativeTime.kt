package io.github.agopalareddy.umm.ui

import java.time.Instant
import java.time.ZoneId
import java.time.chrono.IsoChronology
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * "Just now", "5 minutes ago", "3 hours ago", "Yesterday", "4 days ago", then the date in the locale's order
 * ("Sep 28" this year, "Sep 28, 2025" in an earlier one). Like Android's DateUtils, anything over a day old counts
 * calendar days in [zone].
 */
fun relativeTime(thenMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String {
    val elapsed = (nowMs - thenMs).coerceAtLeast(0)
    val minutes = elapsed / 60_000
    val hours = elapsed / 3_600_000
    val then = Instant.ofEpochMilli(thenMs).atZone(zone)
    val now = Instant.ofEpochMilli(nowMs).atZone(zone)
    val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
    return when {
        minutes < 1 -> "Just now"
        hours < 1 -> plural(minutes, "minute")
        hours < 24 -> plural(hours, "hour")
        days <= 1 -> "Yesterday"
        days < 7 -> "$days days ago"
        then.year == now.year -> then.format(DateTimeFormatter.ofPattern(withoutYear(mediumDatePattern(locale)), locale))
        else -> then.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    }
}

private fun plural(count: Long, unit: String) = if (count == 1L) "1 $unit ago" else "$count ${unit}s ago"

private fun mediumDatePattern(locale: Locale): String =
    DateTimeFormatterBuilder.getLocalizedDateTimePattern(FormatStyle.MEDIUM, null, IsoChronology.INSTANCE, locale)

/** "MMM d, y" → "MMM d", "d MMM y" → "d MMM": the locale's medium date with the year and its separator removed. */
private fun withoutYear(pattern: String): String =
    pattern.replace(Regex("[\\s,./-]*y+[.]?|y+[\\s,./-]*"), "").trim()
