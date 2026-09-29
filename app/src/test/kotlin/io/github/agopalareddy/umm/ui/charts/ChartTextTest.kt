package io.github.agopalareddy.umm.ui.charts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartTextTest {
    @Test fun scaleMaxNeverBelowOne() {
        assertEquals(1f, ChartText.scaleMax(emptyList()), 0f)
        assertEquals(1f, ChartText.scaleMax(listOf(0f, 0f)), 0f)
        assertEquals(1f, ChartText.scaleMax(listOf(0.2f)), 0f)
        assertEquals(7f, ChartText.scaleMax(listOf(3f, 7f)), 0f)
    }

    @Test fun deltaNullWithoutPrevious() {
        assertNull(ChartText.delta(120.0, null))
    }

    @Test fun deltaNewFromZero() {
        assertEquals("new", ChartText.delta(5.0, 0.0))
    }

    @Test fun deltaBothZeroIsNull() {
        assertNull(ChartText.delta(0.0, 0.0))
    }

    @Test fun deltaRoundsPercent() {
        assertEquals("▲ 20%", ChartText.delta(120.0, 100.0))
        assertEquals("▼ 8%", ChartText.delta(92.0, 100.0))
        assertEquals("▲ 0%", ChartText.delta(100.0, 100.0))
        assertEquals("▲ 13%", ChartText.delta(112.6, 100.0))
    }

    @Test fun peakNamesTheLargestPoint() {
        val points = listOf("Mon" to 100.0, "Tue" to 420.0, "Wed" to 0.0)
        assertEquals(
            "Words per day, last 30 days. Most on Tue: 420.",
            ChartText.peak("Words per day", "last 30 days", points) { it.toInt().toString() },
        )
    }

    @Test fun peakFirstLargestWinsOnTie() {
        val points = listOf("Mon" to 50.0, "Tue" to 50.0)
        assertEquals(
            "Words per day, last 7 days. Most on Mon: 50.",
            ChartText.peak("Words per day", "last 7 days", points) { it.toInt().toString() },
        )
    }

    @Test fun peakWithAllZeroesIsEmptySentence() {
        val points = listOf("Mon" to 0.0, "Tue" to 0.0)
        assertEquals(
            "Words per day: no data yet.",
            ChartText.peak("Words per day", "last 30 days", points) { it.toInt().toString() },
        )
        assertEquals("Words per day: no data yet.", ChartText.empty("Words per day"))
        assertEquals(
            "Words per day: no data yet.",
            ChartText.peak("Words per day", "last 30 days", emptyList()) { it.toString() },
        )
    }
}
