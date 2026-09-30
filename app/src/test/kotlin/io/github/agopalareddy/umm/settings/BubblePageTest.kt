package io.github.agopalareddy.umm.settings

import io.github.agopalareddy.umm.bubble.BubbleSetup
import io.github.agopalareddy.umm.core.data.BubbleEdge
import io.github.agopalareddy.umm.core.data.BubbleShowMode
import io.github.agopalareddy.umm.core.data.BubbleSize
import io.github.agopalareddy.umm.core.data.UmmSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BubblePageTest {
    @Test
    fun showModeLabels() {
        assertEquals("When a text field is focused", BubbleShowMode.WHEN_FOCUSED.label())
        assertEquals("Always", BubbleShowMode.ALWAYS.label())
    }

    @Test
    fun sizeLabelsAreTerse() {
        assertEquals("Small", BubbleSize.SMALL.label())
        assertEquals("Medium", BubbleSize.MEDIUM.label())
        assertEquals("Large", BubbleSize.LARGE.label())
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
    fun resetPositionRestoresSpecDefaultsAndKeepsEverythingElse() {
        val moved = UmmSettings(
            bubbleEnabled = true,
            bubbleSize = BubbleSize.LARGE,
            bubbleVisibility = BubbleShowMode.ALWAYS,
            bubbleEdge = BubbleEdge.LEFT,
            bubbleYPortrait = 0.15f,
            bubbleYLandscape = 0.9f,
            disclosureAcceptedAt = 42L,
        )
        val reset = resetBubblePosition(moved)
        assertEquals(BubbleEdge.RIGHT, reset.bubbleEdge)
        assertEquals(0.6f, reset.bubbleYPortrait, 0f)
        assertEquals(0.5f, reset.bubbleYLandscape, 0f)
        assertEquals(moved.copy(bubbleEdge = BubbleEdge.RIGHT, bubbleYPortrait = 0.6f, bubbleYLandscape = 0.5f), reset)
    }

    @Test
    fun declineRequestsNothingAndLeavesTheBubbleOff() {
        val before = UmmSettings()
        val outcome = disclosureOutcome(before, accepted = false, now = 1_000L)
        assertEquals(before, outcome.settings)
        assertFalse(outcome.settings.bubbleEnabled)
        assertEquals(0L, outcome.settings.disclosureAcceptedAt)
        assertFalse(outcome.openAccessibility)
    }

    @Test
    fun acceptStoresConsentAndEnablesTogetherAndOpensAccessibility() {
        val outcome = disclosureOutcome(UmmSettings(), accepted = true, now = 1_000L)
        assertEquals(1_000L, outcome.settings.disclosureAcceptedAt)
        assertTrue(outcome.settings.bubbleEnabled)
        assertTrue(outcome.openAccessibility)
    }

    @Test
    fun firstSwitchOnShowsTheDisclosureAndChangesNothing() {
        assertEquals(SwitchOn.ShowDisclosure, switchOn(UmmSettings(), connected = false))
        assertEquals(SwitchOn.ShowDisclosure, switchOn(UmmSettings(), connected = true))
    }

    @Test
    fun switchOnWithConsentOpensAccessibilityOnlyWhenNotConnected() {
        val consented = UmmSettings(disclosureAcceptedAt = 7L)
        assertEquals(SwitchOn.Enable(openAccessibility = true), switchOn(consented, connected = false))
        assertEquals(SwitchOn.Enable(openAccessibility = false), switchOn(consented, connected = true))
    }

    @Test
    fun awaitingEnableClearsOnlyWhenResumedWithoutTheService() {
        assertTrue(BubbleSetup.shouldClearAwaiting(awaiting = true, connected = false))
        assertFalse(BubbleSetup.shouldClearAwaiting(awaiting = true, connected = true))
        assertFalse(BubbleSetup.shouldClearAwaiting(awaiting = false, connected = false))
        assertFalse(BubbleSetup.shouldClearAwaiting(awaiting = false, connected = true))
    }

    @Test
    fun accessibilityLaunchLiteralsAreTheHiddenFrameworkConstants() {
        assertEquals("android.settings.ACCESSIBILITY_DETAILS_SETTINGS", ACTION_ACCESSIBILITY_DETAILS_SETTINGS)
        assertEquals(
            "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME",
            EXTRA_ACCESSIBILITY_SERVICE_COMPONENT_NAME,
        )
    }

    @Test
    fun accessibilityLaunchTriesUmmsPageThenTheGenericList() {
        val plan = accessibilityLaunchPlan("io.github.agopalareddy.umm/io.github.agopalareddy.umm.bubble.BubbleService")
        assertEquals(
            listOf(
                LaunchSpec(
                    "android.settings.ACCESSIBILITY_DETAILS_SETTINGS",
                    mapOf(
                        "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME" to
                            "io.github.agopalareddy.umm/io.github.agopalareddy.umm.bubble.BubbleService",
                    ),
                ),
                LaunchSpec("android.settings.ACCESSIBILITY_SETTINGS", emptyMap()),
            ),
            plan,
        )
    }
}
