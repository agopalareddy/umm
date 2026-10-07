package io.github.agopalareddy.umm.desktop

import io.github.agopalareddy.umm.desktop.engine.DesktopState
import java.awt.Insets
import java.awt.Rectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OrbModelTest {
    @Test fun activeStatesStayUntilTheDictationMovesOn() {
        assertNull(OrbModel.hideAfterMs(DesktopState.Idle))
        assertNull(OrbModel.hideAfterMs(DesktopState.Listening(500)))
        assertNull(OrbModel.hideAfterMs(DesktopState.Processing))
    }

    @Test fun finalStatesFadeAfterTheTimeNeededToReadThem() {
        assertEquals(1000L, OrbModel.hideAfterMs(DesktopState.Inserted(pastedOnAsciiDesktop = false)))
        assertEquals(4000L, OrbModel.hideAfterMs(DesktopState.Inserted(pastedOnAsciiDesktop = true)))
        assertEquals(1200L, OrbModel.hideAfterMs(DesktopState.NoSpeech))
        assertEquals(4500L, OrbModel.hideAfterMs(DesktopState.Copied("x")))
        assertEquals(4500L, OrbModel.hideAfterMs(DesktopState.Failed("x")))
        assertEquals(4500L, OrbModel.hideAfterMs(DesktopState.NeedsKey))
    }

    @Test fun messages() {
        assertEquals("Done", OrbModel.message(DesktopState.Inserted(false)))
        assertEquals("Done · if nothing appeared, press Ctrl+Shift+V", OrbModel.message(DesktopState.Inserted(true)))
        assertEquals("Copied to the clipboard · Typing permission was refused", OrbModel.message(DesktopState.Copied("Typing permission was refused")))
        assertEquals("Couldn't reach OpenRouter", OrbModel.message(DesktopState.Failed("Couldn't reach OpenRouter")))
        assertEquals("No speech", OrbModel.message(DesktopState.NoSpeech))
        assertEquals("Connect OpenRouter · click to open", OrbModel.message(DesktopState.NeedsKey))
        assertNull(OrbModel.message(DesktopState.Listening(1)))
        assertNull(OrbModel.message(DesktopState.Processing))
        assertNull(OrbModel.message(DesktopState.Idle))
    }

    private val screen = Rectangle(0, 0, 1920, 1080)
    private val noInsets = Insets(0, 0, 0, 0)

    @Test fun bottomCenterSitsAboveTheEdge() {
        assertEquals(890 to 892, OrbModel.place(screen, noInsets, OrbPosition.BOTTOM_CENTER, 140, 140))
    }

    @Test fun cornersKeepTheMargin() {
        assertEquals(48 to 892, OrbModel.place(screen, noInsets, OrbPosition.BOTTOM_LEFT, 140, 140))
        assertEquals(1732 to 892, OrbModel.place(screen, noInsets, OrbPosition.BOTTOM_RIGHT, 140, 140))
        assertEquals(890 to 48, OrbModel.place(screen, noInsets, OrbPosition.TOP_CENTER, 140, 140))
    }

    @Test fun panelsAndOtherMonitorsAreRespected() {
        assertEquals(852, OrbModel.place(screen, Insets(0, 0, 40, 0), OrbPosition.BOTTOM_CENTER, 140, 140).second)
        assertEquals(990, OrbModel.place(Rectangle(100, 0, 1920, 1080), noInsets, OrbPosition.BOTTOM_CENTER, 140, 140).first)
    }
}
