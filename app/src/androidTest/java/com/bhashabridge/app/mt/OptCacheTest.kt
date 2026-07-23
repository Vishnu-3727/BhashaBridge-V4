package com.bhashabridge.app.mt

import android.os.Debug
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
 * Phase 2B proof for the ORT-format + memory-mapped cache. The production policy
 * ([ExecutionPolicy.current], `optCache = true`) bakes an ALL-optimized `.ort` graph once per install
 * and mmaps it NO_OPT thereafter. This drives one **cold** build (cache absent → extract + bake) and
 * one **warm** build (cache present → mmap, no optimization) in a single process — filesDir survives
 * between the two, which a `connectedAndroidTest` uninstall would otherwise wipe.
 *
 * Asserts, all on-device: warm build faster than cold; the three `.ort` graphs + stamps exist; the
 * filesDir source copy and any Phase 2A `.opt` cache are gone after warm (no duplicate storage); and
 * translation output is byte-identical cold and warm. Also logs `.ort` vs source-asset bytes and PSS
 * for the Phase 2A comparison.
 */
@RunWith(AndroidJUnit4::class)
class OptCacheTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // EN→HI source assets and their derived cache files, matching OnnxModels.ortName/stampName.
    private val sources = listOf("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")
    private val probe = "Hello, how are you?"

    @Test
    fun warmBuildMemoryMapsOrtCacheAndPreservesOutput() {
        clearAll() // delete .ort, stamps and any source copy for a true cold start

        val (coldMs, coldOut) = buildAndTranslate()
        assertOrtFilesExist()

        val (warmMs, warmOut) = buildAndTranslate()
        // The source copy the cold bake left behind is purged on this warm launch, leaving only .ort.
        assertNoFilesDirDuplication()

        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        Log.i(TAG, "ORT_CACHE cold=${coldMs}ms warm=${warmMs}ms saved=${coldMs - warmMs}ms")
        Log.i(TAG, "ORT_STORAGE ort_kb=${totalKb { ortFile(it) }} source_kb=${sourceAssetsKb()} (filesDir now holds .ort only)")
        Log.i(TAG, "ORT_MEM totalPss=${mi.totalPss} nativePss=${mi.nativePss} dalvikPss=${mi.dalvikPss}")
        Log.i(TAG, "ORT_CACHE out cold='$coldOut' warm='$warmOut'")

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

    private fun ortFile(name: String) = File(context.filesDir, name.removeSuffix(".onnx") + ".ort")
    private fun stampFile(name: String) = File(context.filesDir, name.removeSuffix(".onnx") + ".ort.stamp")
    private fun totalKb(f: (String) -> File) = sources.sumOf { f(it).length() } / 1024
    private fun sourceAssetsKb() = sources.sumOf { context.assets.openFd(it).use { fd -> fd.length } } / 1024

    private fun assertOrtFilesExist() {
        for (name in sources) {
            assertTrue("missing baked graph ${ortFile(name).name}", ortFile(name).let { it.exists() && it.length() > 0 })
            assertTrue("missing cache stamp ${stampFile(name).name}", stampFile(name).exists())
        }
    }

    /** Phase 2B removes the filesDir source copy and any Phase 2A .opt cache — no duplicate storage. */
    private fun assertNoFilesDirDuplication() {
        for (name in sources) {
            val base = name.removeSuffix(".onnx")
            for (obsolete in listOf(name, "$base.opt.onnx", "$base.opt.stamp")) {
                val f = File(context.filesDir, obsolete)
                assertTrue("obsolete/duplicate file must not exist: ${f.name}", !f.exists())
            }
        }
    }

    private fun clearAll() {
        for (name in sources) {
            ortFile(name).delete(); stampFile(name).delete()
            File(context.filesDir, name).delete() // any leftover source copy
        }
    }

    private companion object {
        const val TAG = "BB_OPTCACHE"
    }
}
