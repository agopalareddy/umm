package io.github.agopalareddy.umm.bubble

/**
 * Coordinates guided enabling within one process: the Bubble page sets [awaitingEnable] before sending the user
 * to Accessibility settings, and [BubbleService] brings Umm back when it connects. In memory only, so a service
 * that connects at boot never pulls the app forward.
 */
internal object BubbleSetup {
    @Volatile var awaitingEnable: Boolean = false

    /**
     * True when the Bubble page is back in front, still [awaiting] the service, and it is not [connected]: the
     * user backed out, so a later enable from system settings must not pull Umm forward. When the page resumes
     * because the service connected, the service has already cleared the flag and [connected] is true.
     */
    fun shouldClearAwaiting(awaiting: Boolean, connected: Boolean): Boolean = awaiting && !connected

    const val EXTRA_OPEN_BUBBLE_PAGE = "open_bubble_page"
}
