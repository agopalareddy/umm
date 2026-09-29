package io.github.agopalareddy.umm.core.stats

import java.time.LocalDate

/** How far back the stats dashboard looks; null days means all time. */
enum class DashboardRange(val days: Int?) {
    D7(7), D30(30), D90(90), ALL(null);

    /**
     * The last N days ending today, inclusive; ALL starts at the first row (or today if none,
     * or if the first row is dated in the future).
     */
    fun window(today: LocalDate, firstRowDay: LocalDate?): ClosedRange<LocalDate> =
        if (days == null) minOf(firstRowDay ?: today, today)..today else today.minusDays(days - 1L)..today

    /** The N days immediately before [window]; null for ALL. */
    fun previousWindow(today: LocalDate): ClosedRange<LocalDate>? =
        days?.let { today.minusDays(2L * it - 1)..today.minusDays(it.toLong()) }
}
