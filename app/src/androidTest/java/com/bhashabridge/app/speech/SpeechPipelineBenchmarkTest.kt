package com.bhashabridge.app.speech

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import com.bhashabridge.app.mt.MtEngine
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 10 measurement harness for the speech half of the pipeline. Times the three stages that a
 * spoken translation pays for — Vosk model load, recognition, machine translation — so the
 * validation report can quote measured numbers instead of estimates.
 *
 * Reads the same fixture as [AudioFileTranscriberTest]: a real 16 kHz WAV, so recognition cost is
 * measured on real audio rather than synthetic buffers. It measures the *pipeline*, not accuracy.
 *
 * Not a change to the benchmark subsystem and not on any production path — it only calls the same
 * public entry points the app uses.
 */
@RunWith(AndroidJUnit4::class)
class SpeechPipelineBenchmarkTest {

    @Test
    fun measuresSpeechToTranslationPipeline() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(copyFixture(app))

        val models = VoskModels(app)
        val loadMs = measure { models.model(Direction.EN_TO_HI) }
        val model = models.model(Direction.EN_TO_HI)
        Log.i(TAG, "VOSK_LOAD_MS=$loadMs")

        var transcript = ""
        val asrMs = measure {
            val events = AudioFileTranscriber.transcribe(app, uri, model).toList()
            transcript = events.filterIsInstance<SpeechEvent.Final>().last().text
        }
        Log.i(TAG, "ASR_MS=$asrMs transcript=\"$transcript\"")

        val engine = MtEngine(app, Direction.EN_TO_HI)
        try {
            // One warm-up so the reported figure is steady-state, matching every other benchmark
            // in this project.
            engine.translate(transcript)
            var output = ""
            val mtMs = measure { output = engine.translate(transcript) }
            Log.i(TAG, "MT_MS=$mtMs output=\"$output\"")
            Log.i(TAG, "PIPELINE_MS=${asrMs + mtMs} (excludes model load and TTS playback)")
            assertTrue(output.isNotBlank())
        } finally {
            engine.release()
            models.release()
        }
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private fun copyFixture(context: Context): File {
        val target = File(context.cacheDir, FIXTURE)
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private companion object {
        const val FIXTURE = "speech_i_need_water.wav"
        const val TAG = "BB_SPEECH_BENCH"
    }
}
