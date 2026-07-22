package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 12 on-device proof for the HI→EN direction, mirroring [MtEngineInstrumentedTest] for EN→HI.
 *
 * The point of these assertions is that HI→EN is the *same* runtime, not a parallel one: the same
 * [MtEngine], the same cached graphs, the same 72-tensor cache contract, differing only in which
 * three `.onnx` assets and which two dictionaries get loaded. Anything direction-specific that had
 * crept into the runtime would show up here as a shape or contract failure, not as bad output.
 */
@RunWith(AndroidJUnit4::class)
class HiEnEngineTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun translatesHindiToEnglishAndReleases() {
        val engine = MtEngine(context, Direction.HI_TO_EN)
        try {
            val english = engine.translate("मुझे पानी चाहिए")
            Log.i(TAG, "HI_TO_EN 'मुझे पानी चाहिए' => '$english'")
            assertTrue("output must not be blank", english.isNotBlank())
            // The target side is English: Devanagari in the output would mean the wrong dictionary
            // or the wrong graph pair was loaded.
            assertFalse(
                "output must not contain Devanagari, got: '$english'",
                english.any { it in 'ऀ'..'ॿ' },
            )
            assertTrue(
                "output must contain Latin letters, got: '$english'",
                english.any { it in 'a'..'z' || it in 'A'..'Z' },
            )
        } finally {
            engine.release()
        }
    }

    @Test
    fun secondTranslationReusesLoadedSessionsAndIsDeterministic() {
        val engine = MtEngine(context, Direction.HI_TO_EN)
        try {
            val a = engine.translate("मेरी मदद करो")
            val b = engine.translate("मेरी मदद करो")
            Log.i(TAG, "HI_TO_EN 'मेरी मदद करो' => '$a' | '$b'")
            assertFalse(a.isBlank())
            assertEquals("same input must decode identically (greedy is deterministic)", a, b)
        } finally {
            engine.release()
        }
    }

    /**
     * The cache contract, direction by direction. Both exports come from the same script against
     * checkpoints with identical geometry (18 layers × 8 heads × 64), so the runtime must see the
     * same 72 cache tensors in the same order, and the step graph must still have no
     * `encoder_hidden_states` input — that pruning is what makes the cross-attention reuse a win.
     */
    @Test
    fun cacheContractMatchesEnHi() {
        val hiEn = OnnxModels(context, Direction.HI_TO_EN)
        try {
            assertEquals("cache tensor count changed", 72, hiEn.pastInputNames.size)
            assertFalse(
                "decoder_step must not expose encoder_hidden_states",
                hiEn.decoderStepSession().inputInfo.containsKey("encoder_hidden_states"),
            )
            val enHi = OnnxModels(context, Direction.EN_TO_HI)
            try {
                assertEquals(
                    "cache ordering differs between directions",
                    enHi.pastInputNames, hiEn.pastInputNames,
                )
            } finally {
                enHi.release()
            }
        } finally {
            hiEn.release()
        }
    }

    private companion object {
        const val TAG = "BB_PARITY"
    }
}
