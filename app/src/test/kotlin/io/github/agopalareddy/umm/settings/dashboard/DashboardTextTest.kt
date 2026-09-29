package io.github.agopalareddy.umm.settings.dashboard

import io.github.agopalareddy.umm.core.stats.DashboardRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardTextTest {
    @Test fun durationPicksTwoUnits() {
        assertEquals("0 s", DashboardText.duration(0))
        assertEquals("44 s", DashboardText.duration(44_999))
        assertEquals("2 min 44 s", DashboardText.duration(164_000))
        assertEquals("1 h 5 min", DashboardText.duration(3_900_000))
        assertEquals("120 h 0 min", DashboardText.duration(432_000_000))
    }

    @Test fun durationClampsNegative() {
        assertEquals("0 s", DashboardText.duration(-5_000))
    }

    @Test fun countGroupsThousands() {
        assertEquals("0", DashboardText.count(0))
        assertEquals("12,345", DashboardText.count(12_345))
    }

    @Test fun daysIsSingularForOne() {
        assertEquals("0 days", DashboardText.days(0))
        assertEquals("1 day", DashboardText.days(1))
        assertEquals("12 days", DashboardText.days(12))
    }

    @Test fun novelShareUnderTenPercentKeepsOneDecimal() {
        assertEquals("1.2% of a novel", DashboardText.novelShare(0.012))
        assertEquals("9.9% of a novel", DashboardText.novelShare(0.0994))
    }

    @Test fun novelShareTinyOrZero() {
        assertEquals("0% of a novel", DashboardText.novelShare(0.0))
        assertEquals("0% of a novel", DashboardText.novelShare(Double.NaN))
        assertEquals("under 0.1% of a novel", DashboardText.novelShare(0.0004))
        assertEquals("0.1% of a novel", DashboardText.novelShare(0.0005))
    }

    @Test fun novelShareWholePercentFromTen() {
        assertEquals("10% of a novel", DashboardText.novelShare(0.0996))
        assertEquals("45% of a novel", DashboardText.novelShare(0.45))
        assertEquals("99% of a novel", DashboardText.novelShare(0.994))
    }

    @Test fun novelShareCountsNovelsFromOne() {
        assertEquals("1 novel", DashboardText.novelShare(0.995))
        assertEquals("1 novel", DashboardText.novelShare(1.02))
        assertEquals("1.3 novels", DashboardText.novelShare(1.25))
        assertEquals("2 novels", DashboardText.novelShare(2.0))
        assertEquals("12 novels", DashboardText.novelShare(12.4))
        assertEquals("1,250 novels", DashboardText.novelShare(1_250.0))
    }

    @Test fun spokenDeltaReadsWords() {
        assertEquals("up 12 percent", DashboardText.spokenDelta("▲ 12%"))
        assertEquals("down 8 percent", DashboardText.spokenDelta("▼ 8%"))
        assertEquals("unchanged", DashboardText.spokenDelta("▲ 0%"))
        assertEquals("up more than 999 percent", DashboardText.spokenDelta("▲ 999%+"))
        assertEquals("down more than 999 percent", DashboardText.spokenDelta("▼ 999%+"))
        assertEquals("new", DashboardText.spokenDelta("new"))
    }

    @Test fun previousPeriodNames() {
        assertEquals("the previous 7 days", DashboardText.previous(DashboardRange.D7))
        assertEquals("the previous 90 days", DashboardText.previous(DashboardRange.D90))
        assertNull(DashboardText.previous(DashboardRange.ALL))
    }
}
