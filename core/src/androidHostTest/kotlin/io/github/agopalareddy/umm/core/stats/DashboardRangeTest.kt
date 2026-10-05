package io.github.agopalareddy.umm.core.stats

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DashboardRangeTest {
    private val today = LocalDate.parse("2026-09-27")

    @Test fun d7WindowIsSevenDaysEndingToday() {
        val w = DashboardRange.D7.window(today, null)
        assertEquals(LocalDate.parse("2026-09-21"), w.start)
        assertEquals(today, w.endInclusive)
    }

    @Test fun previousWindowIsTheSevenDaysBefore() {
        val w = DashboardRange.D7.previousWindow(today)!!
        assertEquals(LocalDate.parse("2026-09-14"), w.start)
        assertEquals(LocalDate.parse("2026-09-20"), w.endInclusive)
    }

    @Test fun allStartsAtFirstRowAndHasNoPrevious() {
        val first = LocalDate.parse("2026-03-01")
        val w = DashboardRange.ALL.window(today, first)
        assertEquals(first, w.start)
        assertEquals(today, w.endInclusive)
        assertNull(DashboardRange.ALL.previousWindow(today))
    }

    @Test fun allWithFutureFirstRowStillEndsToday() {
        val w = DashboardRange.ALL.window(today, LocalDate.parse("2026-10-05"))
        assertEquals(today, w.start)
        assertEquals(today, w.endInclusive)
    }

    @Test fun allWithNoRowsIsJustToday() {
        val w = DashboardRange.ALL.window(today, null)
        assertEquals(today, w.start)
        assertEquals(today, w.endInclusive)
    }
}
