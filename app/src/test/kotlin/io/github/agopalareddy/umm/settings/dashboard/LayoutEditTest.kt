package io.github.agopalareddy.umm.settings.dashboard

import io.github.agopalareddy.umm.core.stats.DashboardLayout
import io.github.agopalareddy.umm.core.stats.Layout as CardLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutEditTest {
    private val defaults = DashboardLayout.DEFAULT_ORDER
    private val fresh = DashboardLayout.merge(emptyList(), emptySet())

    /** What the settings store does to a layout on its way to disk and back. */
    private fun stored(layout: CardLayout): CardLayout {
        val order = layout.order.joinToString(",").split(",").filter { it.isNotBlank() }
        val hidden = layout.hidden.joinToString(",").split(",").filter { it.isNotBlank() }.toSet()
        return DashboardLayout.merge(order, hidden)
    }

    @Test fun hidingAddsToTheHiddenSetAndKeepsOrder() {
        val next = LayoutEdit.setVisible(fresh, "budget", false)
        assertEquals(setOf("budget"), next.hidden)
        assertEquals(defaults, next.order)
        assertEquals(defaults - "budget", next.visible)
    }

    @Test fun showingRemovesFromTheHiddenSet() {
        val hidden = CardLayout(defaults, setOf("budget", "summary"))
        val next = LayoutEdit.setVisible(hidden, "budget", true)
        assertEquals(setOf("summary"), next.hidden)
    }

    @Test fun hidingTwiceOrShowingAVisibleCardChangesNothing() {
        val once = LayoutEdit.setVisible(fresh, "budget", false)
        assertEquals(once, LayoutEdit.setVisible(once, "budget", false))
        assertEquals(fresh, LayoutEdit.setVisible(fresh, "budget", true))
    }

    @Test fun hiddenCardKeepsItsPlaceInTheOrder() {
        val hidden = LayoutEdit.setVisible(fresh, "fun_facts", false)
        val shown = LayoutEdit.setVisible(hidden, "fun_facts", true)
        assertEquals(fresh, shown)
    }

    @Test fun movingUpSwapsWithTheCardAbove() {
        val next = LayoutEdit.move(fresh, "streak_calendar", -1)
        assertEquals(listOf("streak_calendar", "summary"), next.order.take(2))
        assertEquals(fresh.hidden, next.hidden)
    }

    @Test fun movingDownSwapsWithTheCardBelow() {
        val next = LayoutEdit.move(fresh, "summary", 1)
        assertEquals(listOf("streak_calendar", "summary"), next.order.take(2))
    }

    @Test fun movingKeepsTheHiddenSet() {
        val start = CardLayout(defaults, setOf("budget"))
        assertEquals(setOf("budget"), LayoutEdit.move(start, "budget", -1).hidden)
    }

    @Test fun movingPastAnEndOrOfAnUnknownCardChangesNothing() {
        assertEquals(fresh, LayoutEdit.move(fresh, defaults.first(), -1))
        assertEquals(fresh, LayoutEdit.move(fresh, defaults.last(), 1))
        assertEquals(fresh, LayoutEdit.move(fresh, "bogus", 1))
    }

    @Test fun firstCardCannotMoveUpAndLastCannotMoveDown() {
        assertFalse(LayoutEdit.canMove(fresh, defaults.first(), -1))
        assertTrue(LayoutEdit.canMove(fresh, defaults.first(), 1))
        assertFalse(LayoutEdit.canMove(fresh, defaults.last(), 1))
        assertTrue(LayoutEdit.canMove(fresh, defaults.last(), -1))
    }

    @Test fun middleCardCanMoveBothWays() {
        assertTrue(LayoutEdit.canMove(fresh, "budget", -1))
        assertTrue(LayoutEdit.canMove(fresh, "budget", 1))
    }

    @Test fun hiddenCardsStillCountForTheEnds() {
        // Every row shows in Edit mode, so the ends are the ends of the whole order.
        val start = CardLayout(defaults, setOf(defaults.first(), defaults.last()))
        assertFalse(LayoutEdit.canMove(start, defaults.first(), -1))
        assertTrue(LayoutEdit.canMove(start, defaults[1], -1))
    }

    @Test fun unknownCardCannotMove() {
        assertFalse(LayoutEdit.canMove(fresh, "bogus", 1))
        assertFalse(LayoutEdit.canMove(fresh, "bogus", -1))
    }

    @Test fun hidingEveryCardLeavesNothingVisible() {
        val none = defaults.fold(fresh) { layout, id -> LayoutEdit.setVisible(layout, id, false) }
        assertTrue(none.visible.isEmpty())
        assertEquals(defaults.toSet(), none.hidden)
    }

    @Test fun editsSurviveTheStoredForm() {
        var layout = fresh
        layout = LayoutEdit.move(layout, "reliability", -3)
        layout = LayoutEdit.move(layout, "summary", 2)
        layout = LayoutEdit.setVisible(layout, "budget", false)
        layout = LayoutEdit.setVisible(layout, "milestones", false)
        assertEquals(layout, stored(layout))
    }

    @Test fun anAllHiddenLayoutSurvivesTheStoredForm() {
        val none = CardLayout(defaults, defaults.toSet())
        assertEquals(none, stored(none))
    }

    @Test fun resetWritesNothingAndReadsAsTheDefault() {
        assertEquals(emptyList<String>(), LayoutEdit.RESET_ORDER)
        assertEquals(emptySet<String>(), LayoutEdit.RESET_HIDDEN)
        val reset = DashboardLayout.merge(LayoutEdit.RESET_ORDER, LayoutEdit.RESET_HIDDEN)
        assertEquals(fresh, reset)
        assertEquals(defaults, reset.visible)
    }

    @Test fun aStoredOrderWithDuplicatesEditsCleanly() {
        val saved = DashboardLayout.merge(listOf("budget", "summary", "budget", "bogus"), setOf("bogus", "summary"))
        val next = LayoutEdit.move(saved, "summary", -1)
        assertEquals(listOf("summary", "budget"), next.order.take(2))
        assertEquals(defaults.size, next.order.size)
        assertEquals(setOf("summary"), next.hidden)
    }

    @Test fun everyRegisteredCardHasARowInTheDefaultOrder() {
        assertEquals(defaults.toSet(), Cards.byId.keys)
        assertEquals(defaults.size, fresh.order.size)
    }
}
