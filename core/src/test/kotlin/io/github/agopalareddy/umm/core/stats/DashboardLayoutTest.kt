package io.github.agopalareddy.umm.core.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardLayoutTest {
    private val defaults = DashboardLayout.DEFAULT_ORDER

    @Test fun emptySavedStateGivesDefaultOrder() {
        val l = DashboardLayout.merge(emptyList(), emptySet())
        assertEquals(defaults, l.order)
        assertTrue(l.hidden.isEmpty())
        assertEquals(14, l.order.size)
    }

    @Test fun unknownIdsAreDropped() {
        val l = DashboardLayout.merge(listOf("nope", "budget", "gone"), emptySet())
        assertEquals("budget", l.order.first())
        assertTrue("nope" !in l.order && "gone" !in l.order)
        assertEquals(14, l.order.size)
    }

    @Test fun duplicateIdsKeepTheFirstOccurrence() {
        val l = DashboardLayout.merge(listOf("budget", "summary", "budget"), emptySet())
        assertEquals(listOf("budget", "summary"), l.order.take(2))
        assertEquals(14, l.order.size)
        assertEquals(14, l.order.toSet().size)
    }

    @Test fun newIdsAreAppendedInDefaultOrder() {
        val saved = listOf("budget", "summary", "reliability")
        val l = DashboardLayout.merge(saved, emptySet())
        assertEquals(saved, l.order.take(3))
        assertEquals(defaults.filter { it !in saved }, l.order.drop(3))
    }

    @Test fun hiddenIsPrunedToKnownIds() {
        val l = DashboardLayout.merge(emptyList(), setOf("budget", "bogus"))
        assertEquals(setOf("budget"), l.hidden)
    }

    @Test fun visibleExcludesHidden() {
        val l = DashboardLayout.merge(emptyList(), setOf("summary", "budget"))
        assertEquals(defaults - setOf("summary", "budget"), l.visible)
    }

    @Test fun allHiddenGivesEmptyVisible() {
        val l = DashboardLayout.merge(emptyList(), defaults.toSet())
        assertTrue(l.visible.isEmpty())
    }

    @Test fun moveClampsAtEnds() {
        assertEquals(defaults, DashboardLayout.move(defaults, defaults.first(), -1))
        assertEquals(defaults, DashboardLayout.move(defaults, defaults.last(), 1))
    }

    @Test fun moveSwapsNeighbours() {
        val up = DashboardLayout.move(defaults, "streak_calendar", -1)
        assertEquals(listOf("streak_calendar", "summary"), up.take(2))
        val down = DashboardLayout.move(defaults, "summary", 1)
        assertEquals(listOf("streak_calendar", "summary"), down.take(2))
        assertEquals(defaults.drop(2), down.drop(2))
    }
}
