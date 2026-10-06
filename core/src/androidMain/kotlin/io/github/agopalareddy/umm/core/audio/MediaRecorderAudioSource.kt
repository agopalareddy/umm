package io.github.agopalareddy.umm.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/** Records mono AAC (.m4a, 16 kHz, 32 kbps) and polls MediaRecorder's peak amplitude every 100 ms. */
class MediaRecorderAudioSource(private val context: Context) : AudioSource {
    override val format = "m4a"

    @Volatile private var stopRequested = false

    override fun record(file: File): Flow<Int> = flow {
        stopRequested = false
        var failure: IOException? = null
        val recorder = newRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16_000)
            setAudioEncodingBitRate(32_000)
            setOutputFile(file.path)
            setOnErrorListener { _, what, extra -> failure = IOException("MediaRecorder error $what/$extra") }
            prepare()
            start()
        }
        try {
            while (!stopRequested) {
                failure?.let { throw it }
                emit(recorder.maxAmplitude)
                delay(100)
            }
        } finally {
            // stop() throws when almost nothing was recorded; the file is then unusable either way.
            runCatching { recorder.stop() }
            recorder.release()
        }
    }.flowOn(Dispatchers.IO)

    override fun stop() {
        stopRequested = true
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
}
