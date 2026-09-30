package io.github.agopalareddy.umm.bubble

/**
 * Coordinates guided enabling within one process: the Bubble page sets [awaitingEnable] before sending the user
 * to Accessibility settings, and [BubbleService] brings Umm back when it connects. In memory only, so a service
 * that connects at boot never pulls the app forward.
 */
internal object BubbleSetup {
    @Volatile var awaitingEnable: Boolean = false

    const val EXTRA_OPEN_BUBBLE_PAGE = "open_bubble_page"
}
