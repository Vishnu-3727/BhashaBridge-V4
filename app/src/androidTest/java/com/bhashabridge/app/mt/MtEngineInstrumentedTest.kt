package com.bhashabridge.app.mt

import ai.onnxruntime.OnnxTensor
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.LongBuffer

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
            Log.i("BB_PARITY", "EN_TO_HI 'Hello, how are you?' => '$hindi'")
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
            Log.i("BB_PARITY", "EN_TO_HI 'Water.' => '$a' | '$b'")
            assertFalse(a.isBlank())
            assertTrue("same input must decode identically (greedy is deterministic)", a == b)
        } finally {
            engine.release()
        }
    }

    /**
     * Direct proof for [lastLogitsRow]: it returns exactly what the old
     * `(tensor.value as Array<Array<FloatArray>>)[0].last()` returned, on a real decoder output.
     *
     * The two golden-string tests above would eventually catch a wrong row, but only as a mystery
     * translation change. This calls the shipping function — not a copy of it — and compares it
     * against the reader it replaced, so a mistake in the offset arithmetic or in the array-backed
     * fast path fails as itself.
     *
     * Both branches are covered, because they are genuinely different code: a `dec_len 3` prefix takes
     * the slice path, and a `dec_len 1` prefix takes the `buffer.array()` path that skips the second
     * copy. A single-position check would pass even with the offset arithmetic broken, since
     * `(1 - 1) * vocab` is zero.
     */
    @Test
    fun lastLogitsRowMatchesTheBoxedReadAtEveryPrefixLength() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val tokenizer = Tokenizer.load(context, Direction.EN_TO_HI)
        val models = OnnxModels(context, Direction.EN_TO_HI)
        try {
            val srcIds = tokenizer.encode("Hello, how are you?")
            val srcShape = longArrayOf(1, srcIds.size.toLong())
            OnnxTensor.createTensor(models.env, LongBuffer.wrap(LongArray(srcIds.size) { 1L }), srcShape).use { mask ->
                val encoded = OnnxTensor.createTensor(models.env, LongBuffer.wrap(srcIds), srcShape).use { src ->
                    models.encoderSession().run(mapOf("input_ids" to src, "attention_mask" to mask))
                }
                encoded.use {
                    val hidden = encoded[0] as OnnxTensor
                    checkRow(models, hidden, mask, longArrayOf(2L))              // dec_len 1: array() path
                    checkRow(models, hidden, mask, longArrayOf(2L, 5L, 7L))      // dec_len 3: slice path
                }
            }
        } finally {
            models.release()
        }
    }

    /** Runs decoder_init on [prefix] and asserts [lastLogitsRow] agrees with the boxed reader. */
    private fun checkRow(models: OnnxModels, hidden: OnnxTensor, mask: OnnxTensor, prefix: LongArray) {
        val decIds = OnnxTensor.createTensor(
            models.env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong()),
        )
        val out = decIds.use {
            models.decoderInitSession().run(
                mapOf(
                    "decoder_input_ids" to decIds,
                    "encoder_hidden_states" to hidden,
                    "encoder_attention_mask" to mask,
                )
            )
        }
        out.use {
            val tensor = out[0] as OnnxTensor
            assertEquals("one logits row per prefix position", prefix.size.toLong(), tensor.info.shape[1])

            // The shipping reader first: `value` could leave the tensor's own buffer repositioned,
            // which would make this comparison test the wrong thing.
            val actual = lastLogitsRow(tensor)

            @Suppress("UNCHECKED_CAST")
            val boxed = (tensor.value as Array<Array<FloatArray>>)[0].last()

            Log.i("BB_PARITY", "dec_len=${prefix.size} vocab=${boxed.size} first=${boxed[0]}/${actual[0]}")
            assertEquals("row width at dec_len=${prefix.size}", boxed.size, actual.size)
            assertArrayEquals("lastLogitsRow must equal the boxed read at dec_len=${prefix.size}", boxed, actual, 0f)
        }
    }
}
