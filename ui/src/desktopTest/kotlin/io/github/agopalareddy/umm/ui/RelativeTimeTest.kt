package io.github.agopalareddy.umm.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {
    @Test fun relativeTime_buckets() {
        val now = 1_800_000_000_000L
        assertEquals("Just now", relativeTime(now - 30_000, now))
        assertEquals("5 minutes ago", relativeTime(now - 5 * 60_000, now))
        assertEquals("1 minute ago", relativeTime(now - 60_000, now))
        assertEquals("3 hours ago", relativeTime(now - 3 * 3_600_000, now))
        assertEquals("1 hour ago", relativeTime(now - 3_600_000, now))
        assertEquals("Yesterday", relativeTime(now - 30 * 3_600_000, now))
        assertEquals("4 days ago", relativeTime(now - 4 * 86_400_000L, now))
    }

    @Test fun olderThanAWeek_showsTheDate() {
        val zone = ZoneId.of("America/Chicago")
        val now = ZonedDateTime.of(2027, 1, 15, 12, 0, 0, 0, zone).toInstant().toEpochMilli()
        val sameYear = ZonedDateTime.of(2027, 1, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        val lastYear = ZonedDateTime.of(2026, 9, 28, 9, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals("Jan 2", relativeTime(sameYear, now, zone))
        assertEquals("Sep 28, 2026", relativeTime(lastYear, now, zone))
    }

    @Test fun futureTimes_countAsJustNow() {
        assertEquals("Just now", relativeTime(1_000_060_000L, 1_000_000_000L))
    }
}
