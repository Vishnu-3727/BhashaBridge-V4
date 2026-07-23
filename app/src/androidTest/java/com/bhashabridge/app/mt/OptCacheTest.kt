package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 2A proof for the on-disk optimized-graph cache. The production policy
 * ([ExecutionPolicy.current], `optCache = true`) bakes an ALL-optimized graph once per install and
 * loads it NO_OPT thereafter; this test drives one **cold** build (cache absent → bake) and one
 * **warm** build (cache present → skip optimization) in a single process — filesDir survives between
 * the two, which a `connectedAndroidTest` uninstall would otherwise wipe.
 *
 * What it asserts, all on-device:
 *  - the warm build is faster than the cold one (graph optimization no longer runs), and
 *  - the three optimized graphs + stamps land on disk, and
 *  - translation output is byte-identical cold and warm (the cache changes startup, not results).
 */
@RunWith(AndroidJUnit4::class)
class OptCacheTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // EN→HI source assets and their derived cache files, matching OnnxModels.optName/stampName.
    private val sources = listOf("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")
    private val probe = "Hello, how are you?"

    @Test
    fun warmBuildSkipsOptimizationAndPreservesOutput() {
        // Guarantee the source graphs are extracted first, so the cold build below measures baking,
        // not asset extraction. Also clears any cache left by a previous run for a true cold start.
        MtEngine(context, Direction.EN_TO_HI).release()
        clearCache()

        val (coldMs, coldOut) = buildAndTranslate()
        assertOptFilesExist()

        val (warmMs, warmOut) = buildAndTranslate()

        Log.i(TAG, "OPT_CACHE cold=${coldMs}ms warm=${warmMs}ms saved=${coldMs - warmMs}ms")
        Log.i(TAG, "OPT_CACHE out cold='$coldOut' warm='$warmOut'")

        assertTrue("output must not be blank", coldOut.isNotBlank())
        assertEquals("cache must not change translation output", coldOut, warmOut)
        assertTrue(
            "warm build (${warmMs}ms) must beat cold build (${coldMs}ms) — optimization should be skipped",
            warmMs < coldMs,
        )
    }

    private fun buildAndTranslate(): Pair<Long, String> {
        val start = System.nanoTime()
        val engine = MtEngine(context, Direction.EN_TO_HI)
        val buildMs = (System.nanoTime() - start) / 1_000_000
        return try {
            buildMs to engine.translate(probe)
        } finally {
            engine.release()
        }
    }

    private fun assertOptFilesExist() {
        for (name in sources) {
            val opt = File(context.filesDir, name.removeSuffix(".onnx") + ".opt.onnx")
            val stamp = File(context.filesDir, name.removeSuffix(".onnx") + ".opt.stamp")
            assertTrue("missing baked graph ${opt.name}", opt.exists() && opt.length() > 0)
            assertTrue("missing cache stamp ${stamp.name}", stamp.exists())
        }
    }

    private fun clearCache() {
        for (name in sources) {
            File(context.filesDir, name.removeSuffix(".onnx") + ".opt.onnx").delete()
            File(context.filesDir, name.removeSuffix(".onnx") + ".opt.stamp").delete()
        }
    }

    private companion object {
        const val TAG = "BB_OPTCACHE"
    }
}
