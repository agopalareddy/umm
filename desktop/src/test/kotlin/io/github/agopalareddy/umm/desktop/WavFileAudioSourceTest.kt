package io.github.agopalareddy.umm.desktop

import java.io.File
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavFileAudioSourceTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun emitsPeakPer100ms() = runBlocking {
        val source = writeWav(tmp.newFile("in.wav"), 0 to 500, 1000 to 500)
        val target = File(tmp.root, "out.wav")
        val levels = WavFileAudioSource(source).record(target).toList()
        assertEquals(listOf(0, 0, 0, 0, 0, 1000, 1000, 1000, 1000, 1000), levels)
        assertArrayEquals(source.readBytes(), target.readBytes())
    }

    @Test fun stopEndsTheRecording() = runBlocking {
        val audio = WavFileAudioSource(writeWav(tmp.newFile("in.wav"), 1000 to 1000))
        val levels = mutableListOf<Int>()
        audio.record(File(tmp.root, "out.wav")).collect { levels += it; if (levels.size == 3) audio.stop() }
        assertEquals(3, levels.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonWav() {
        WavFileAudioSource(tmp.newFile("notes.txt").apply { writeText("hello, not audio") })
    }

    @Test fun formatIsWav() {
        assertEquals("wav", WavFileAudioSource(writeWav(tmp.newFile("in.wav"), 0 to 100)).format)
    }
}
