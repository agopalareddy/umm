package io.github.agopalareddy.umm.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    private val zone = ZoneId.of("America/Chicago")
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun relativeTime_buckets() {
        val now = at(2027, 1, 15, 20)
        assertEquals("Just now", relativeTime(now - 30_000, now, zone))
        assertEquals("5 minutes ago", relativeTime(now - 5 * 60_000, now, zone))
        assertEquals("1 minute ago", relativeTime(now - 60_000, now, zone))
        assertEquals("3 hours ago", relativeTime(now - 3 * 3_600_000, now, zone))
        assertEquals("1 hour ago", relativeTime(now - 3_600_000, now, zone))
        assertEquals("Yesterday", relativeTime(now - 30 * 3_600_000, now, zone))
        assertEquals("4 days ago", relativeTime(now - 4 * 86_400_000L, now, zone))
    }

    /** Like Android's DateUtils: past a day, count calendar days in the zone, not 24-hour periods. */
    @Test fun daysCountCalendarDays() {
        val now = at(2027, 1, 15, 2)
        assertEquals("2 days ago", relativeTime(at(2027, 1, 13, 20), now, zone)) // 30 hours, but two midnights back
        assertEquals("Yesterday", relativeTime(at(2027, 1, 14, 1), now, zone))
        assertEquals("23 hours ago", relativeTime(at(2027, 1, 14, 3), now, zone))
    }

    @Test fun aWeekOrMore_showsTheDate() {
        val now = at(2027, 1, 15, 12)
        assertEquals("6 days ago", relativeTime(at(2027, 1, 9, 12), now, zone, Locale.US))
        assertEquals("Jan 8", relativeTime(at(2027, 1, 8, 12), now, zone, Locale.US))
        assertEquals("Jan 2", relativeTime(at(2027, 1, 2, 9), now, zone, Locale.US))
        assertEquals("Sep 28, 2026", relativeTime(at(2026, 9, 28, 9), now, zone, Locale.US))
    }

    @Test fun datesFollowTheLocaleOrder() {
        val now = at(2027, 1, 15, 12)
        assertEquals("2 Jan", relativeTime(at(2027, 1, 2, 9), now, zone, Locale.UK))
        assertEquals("28 Sept 2026", relativeTime(at(2026, 9, 28, 9), now, zone, Locale.UK))
    }

    @Test fun futureTimes_countAsJustNow() {
        assertEquals("Just now", relativeTime(1_000_060_000L, 1_000_000_000L, zone))
    }
}
