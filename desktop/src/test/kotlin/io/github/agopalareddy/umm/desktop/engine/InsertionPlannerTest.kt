package io.github.agopalareddy.umm.desktop.engine

import io.github.agopalareddy.umm.linux.InsertPart.Paste
import io.github.agopalareddy.umm.linux.InsertPart.Type
import io.github.agopalareddy.umm.linux.TypingCapability.ALL
import io.github.agopalareddy.umm.linux.TypingCapability.ASCII
import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionPlannerTest {
    @Test fun all_typesEverything() {
        assertEquals(listOf(Type("héllo 👍")), InsertionPlanner.plan("héllo 👍", ALL))
    }

    @Test fun ascii_splitsRuns() {
        assertEquals(
            listOf(Type("Sounds good "), Paste("👍"), Type(" thanks")),
            InsertionPlanner.plan("Sounds good 👍 thanks", ASCII),
        )
    }

    @Test fun ascii_keepsNewlinesAndTabsTyped() {
        assertEquals(listOf(Type("- a\n- b\tc")), InsertionPlanner.plan("- a\n- b\tc", ASCII))
    }

    @Test fun ascii_devanagariPasted() {
        assertEquals(
            listOf(Paste("कल"), Type(" meeting "), Paste("है")),
            InsertionPlanner.plan("कल meeting है", ASCII),
        )
    }

    @Test fun surrogatePairNotSplit() {
        assertEquals(listOf(Paste("👍🏽")), InsertionPlanner.plan("👍🏽", ASCII))
    }

    @Test fun empty_noParts() {
        assertEquals(emptyList<Any>(), InsertionPlanner.plan("", ASCII))
        assertEquals(emptyList<Any>(), InsertionPlanner.plan("", ALL))
    }
}
