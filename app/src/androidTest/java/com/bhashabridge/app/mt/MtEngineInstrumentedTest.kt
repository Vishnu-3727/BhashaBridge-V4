package com.bhashabridge.app.mt

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device end-to-end proof for Phase 5: one real EN→HI translation on the SM-M315F, then native
 * cleanup. Runs against the app's real assets (the int8 ONNX models + dictionaries), so it exercises
 * tokenizer, encoder, the greedy [Decoder], and detokenize together — the whole runtime.
 *
 * Requires the ~278 MB of gitignored assets to be present in the APK; without them
 * `env.createSession` throws and the test fails loudly rather than skipping, which is correct on the
 * device where the whole point is that the models are there.
 */
@RunWith(AndroidJUnit4::class)
class MtEngineInstrumentedTest {

    @Test
    fun translatesEnglishToHindiAndReleases() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = MtEngine(context, Direction.EN_TO_HI)
        try {
            val hindi = engine.translate("Hello, how are you?")
            assertTrue("output must not be blank", hindi.isNotBlank())
            assertTrue(
                "output must contain Devanagari, got: '$hindi'",
                hindi.any { it in 'ऀ'..'ॿ' },
            )
        } finally {
            engine.release() // native cleanup must complete without throwing
        }
    }

    @Test
    fun secondTranslationReusesLoadedSessions() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val engine = MtEngine(context, Direction.EN_TO_HI)
        try {
            val a = engine.translate("Water.")
            val b = engine.translate("Water.")
            assertFalse(a.isBlank())
            assertTrue("same input must decode identically (greedy is deterministic)", a == b)
        } finally {
            engine.release()
        }
    }
}
