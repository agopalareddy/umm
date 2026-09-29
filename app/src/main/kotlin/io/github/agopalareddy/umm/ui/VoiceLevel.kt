package io.github.agopalareddy.umm.ui

import kotlin.math.sqrt

/**
 * Maps the recorder's raw amplitude (0..32767) to how strongly the orb should react (0..1).
 *
 * Measured on a Pixel 9 Pro: a quiet room reads about 60-110 and speech about 250-550, so a linear scale would
 * barely move. Below [FLOOR] is treated as silence; the square root lifts normal speech into a visible range.
 */
internal object VoiceLevel {
    const val FLOOR = 120
    const val CEIL = 1600

    fun of(amplitude: Int): Float = sqrt(((amplitude - FLOOR).toFloat() / (CEIL - FLOOR)).coerceIn(0f, 1f))
}
