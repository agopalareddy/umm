package io.github.agopalareddy.umm.settings

import io.github.agopalareddy.umm.core.data.BubbleEdge
import io.github.agopalareddy.umm.core.data.BubbleShowMode
import io.github.agopalareddy.umm.core.data.BubbleSize
import io.github.agopalareddy.umm.core.data.UmmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePageTest {
    @Test
    fun showModeLabelsAndDescriptions() {
        assertEquals("When a text field is focused", BubbleShowMode.WHEN_FOCUSED.label())
        assertEquals("Always", BubbleShowMode.ALWAYS.label())
        assertTrue(BubbleShowMode.WHEN_FOCUSED.description().contains("400 ms"))
        assertTrue(BubbleShowMode.ALWAYS.description().contains("password"))
    }

    @Test
    fun sizeLabelsContainDpValues() {
        assertEquals("Small (44 dp)", BubbleSize.SMALL.label())
        assertEquals("Medium (56 dp)", BubbleSize.MEDIUM.label())
        assertEquals("Large (72 dp)", BubbleSize.LARGE.label())
    }

    @Test
    fun disclosureTextMatchesSpecVerbatim() {
        assertEquals(
            "Umm needs the Accessibility permission for the floating button.",
            DISCLOSURE_TITLE,
        )
        val expected =
            "It is used to see which text field you have selected, so it can put your dictated text there, " +
                "and which app it is in, to pick the cleanup level. Umm reads only the selected field's current " +
                "text and cursor position, never password fields, and never reads or stores anything else on your " +
                "screen. Your speech goes to OpenRouter as usual; nothing else leaves your phone."
        assertEquals(expected, DISCLOSURE_TEXT)
    }

    @Test
    fun resetValuesMatchSpecDefaults() {
        val reset = UmmSettings().copy(
            bubbleEdge = BubbleEdge.RIGHT,
            bubbleYPortrait = 0.6f,
            bubbleYLandscape = 0.5f,
        )
        assertEquals(BubbleEdge.RIGHT, reset.bubbleEdge)
        assertEquals(0.6f, reset.bubbleYPortrait, 0.001f)
        assertEquals(0.5f, reset.bubbleYLandscape, 0.001f)
    }
}
