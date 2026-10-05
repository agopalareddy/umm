package io.github.agopalareddy.umm.core.audio

import java.io.File
import kotlinx.coroutines.flow.Flow

interface AudioSource {
    /** File extension and OpenRouter audio format of the recordings, e.g. `m4a`. */
    val format: String

    /** Starts recording to [file] and emits amplitude every 100 ms. Ends normally after [stop]; throws if the device interrupts recording. */
    fun record(file: File): Flow<Int>

    fun stop()
}
