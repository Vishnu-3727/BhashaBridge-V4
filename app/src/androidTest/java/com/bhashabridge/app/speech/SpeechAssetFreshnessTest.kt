package com.bhashabridge.app.speech

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Guards the two halves of the Hindi decode retune (§3.34): that the narrowed configuration is what
 * the shipped asset says, and that it is what actually reaches a device which already had the model
 * unpacked from an earlier build.
 *
 * The second half is the one that needs a test. `filesDir` survives app updates and
 * [AssetFolder.unpack] used to return early whenever the destination existed, so a retuned
 * `conf/model.conf` reached new installs only — every existing user kept the old decode width with
 * nothing to show that anything had gone wrong. A stamp mismatch now forces a re-unpack, and this is
 * what proves it: the sweep in [AsrTuningBenchmarkTest] deliberately writes the *stock* text back
 * into `filesDir` when it finishes, so on any device that has run it, this test starts from a stale
 * unpacked model — exactly the upgrade case, reproduced for free.
 */
@RunWith(AndroidJUnit4::class)
class SpeechAssetFreshnessTest {

    @Test
    fun shippedHindiDecodeWidthReachesTheDevice() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val unpacked = File(AssetFolder.unpack(context, "model-hi"), "conf/model.conf")
        assertTrue("model-hi/conf/model.conf was not unpacked", unpacked.isFile)

        val values = unpacked.readLines()
            .filter { it.startsWith("--") && it.contains('=') }
            .associate { it.removePrefix("--").substringBefore('=') to it.substringAfter('=').trim() }

        // The retune: what makes Hindi keep up with noisy speech (1.91x -> 0.76x realtime at 10 dB).
        assertEquals("max-active", "3000", values["max-active"])
        assertEquals("beam", "10.0", values["beam"])
        assertEquals("lattice-beam", "2.0", values["lattice-beam"])
        // Untouched by the retune, and named here so a wholesale overwrite of the file is caught
        // rather than passing because the three interesting lines happen to be right.
        assertEquals("min-active", "200", values["min-active"])
        assertEquals("frame-subsampling-factor", "3", values["frame-subsampling-factor"])
    }

    /**
     * The narrowed model still recognises. Asserted on the English fixture through the *Hindi* model
     * being wrong is not the check here — this loads the Hindi model and confirms it produces a
     * transcript at all, which is what a beam narrowed too far would break outright.
     */
    @Test
    fun hindiModelStillTranscribes() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val target = File(context.cacheDir, FIXTURE)
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        val model = VoskModels(context).model(Direction.HI_TO_EN)
        val events = AudioFileTranscriber.transcribe(context, Uri.fromFile(target), model).toList()
        val final = events.filterIsInstance<SpeechEvent.Final>().lastOrNull()

        assertTrue("no Final event; got ${events.size} events", final != null)
        assertTrue("empty transcript", final!!.text.isNotBlank())
        // The word the fixture is built around, and the one the app's own domain cares about.
        assertTrue("unexpected transcript: ${final.text}", final.text.contains("पानी"))
    }

    private companion object {
        const val FIXTURE = "speech_hi_paani.wav"
    }
}
