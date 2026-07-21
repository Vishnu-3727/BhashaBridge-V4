package com.bhashabridge.app.speech

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the audio-import path: a real WAV goes in, a transcript comes out.
 *
 * The fixture (`speech_i_need_water.wav`, 16 kHz mono, "I need water please help me") is synthesised
 * speech, not a recording, so this asserts that recognition *happened* — that decode, downmix,
 * resample and the Vosk feed all line up — rather than pinning an exact word-error rate to one
 * voice.
 */
@RunWith(AndroidJUnit4::class)
class AudioFileTranscriberTest {

    @Test
    fun transcribesWavFile() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(copyFixture(app))
        // Not released: the model must outlive the transcription that uses it, and the test process
        // ends immediately after. Production ownership is process-scoped for the same reason.
        val model = VoskModels(app).model(Direction.EN_TO_HI)

        val events = AudioFileTranscriber.transcribe(app, uri, model).toList()
        val final = events.filterIsInstance<SpeechEvent.Final>().lastOrNull()

        assertTrue("no Final event; got $events", final != null)
        assertTrue("empty transcript", final!!.text.isNotBlank())
        // The keyword every voice in this fixture produces; the rest is voice-dependent.
        assertTrue("unexpected transcript: ${final.text}", final.text.contains("water", true))
    }

    /** MediaExtractor needs a path, and androidTest assets live in the test APK, not the app's. */
    private fun copyFixture(context: Context): File {
        val target = File(context.cacheDir, FIXTURE)
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private companion object {
        const val FIXTURE = "speech_i_need_water.wav"
    }
}
