package io.github.agopalareddy.umm.linux

import io.github.agopalareddy.umm.linux.SingleInstance.Message
import org.junit.Assert.assertEquals
import org.junit.Test

class SingleInstanceTest {
    @Test fun noArgumentsActivate() {
        assertEquals(Message.Activate, SingleInstance.messageFor(emptyArray()))
    }

    @Test fun backgroundFlagActivates() {
        assertEquals(Message.Activate, SingleInstance.messageFor(arrayOf("--background")))
    }

    @Test fun ummLinkIsForwardedAsUrl() {
        assertEquals(Message.OpenUrl("umm://oauth?code=a"), SingleInstance.messageFor(arrayOf("umm://oauth?code=a")))
        assertEquals(
            Message.OpenUrl("umm://oauth?code=a"),
            SingleInstance.messageFor(arrayOf("--background", "umm://oauth?code=a")),
        )
    }
}
