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
 * Proof for the baked graph cache. The production policy ([ExecutionPolicy.current],
 * `optCache = true`) bakes an ALL-optimized graph once per install and loads it NO_OPT thereafter.
 * This drives one **cold** build (cache absent → extract + bake) and one **warm** build (cache
 * present, no optimization) in a single process — filesDir survives between the two, which a
 * `connectedAndroidTest` uninstall would otherwise wipe.
 *
 * Q21 (§3.47) changed the artifact from an ORT flatbuffer to optimized ONNX that still references the
 * shared weight blob, so two of the assertions changed with it: the baked files are `.opt.onnx`, and
 * **`weights.bin` must survive** the warm launch. It used to be deleted at the start of every launch
 * as bake scratch; now every load resolves initializers through it, and a purge would break the app
 * on the second run rather than free space. That is the regression this test exists to catch.
 *
 * Asserts, all on-device: warm build faster than cold; the three baked graphs + stamps + the blob
 * exist; the filesDir source copy and the superseded `.ort` cache are gone after warm; and
 * translation output is byte-identical cold and warm.
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
        Log.i(TAG, "ORT_STORAGE baked_kb=${totalKb { ortFile(it) }} blob_kb=${blobFile().length() / 1024} source_kb=${sourceAssetsKb()}")
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

    private fun ortFile(name: String) = File(context.filesDir, name.removeSuffix(".onnx") + ".opt.onnx")
    private fun stampFile(name: String) = File(context.filesDir, name.removeSuffix(".onnx") + ".opt.stamp")
    private fun blobFile() = File(context.filesDir, "weights.bin")
    private fun totalKb(f: (String) -> File) = sources.sumOf { f(it).length() } / 1024
    private fun sourceAssetsKb() = sources.sumOf { context.assets.openFd(it).use { fd -> fd.length } } / 1024

    private fun assertOrtFilesExist() {
        for (name in sources) {
            assertTrue("missing baked graph ${ortFile(name).name}", ortFile(name).let { it.exists() && it.length() > 0 })
            assertTrue("missing cache stamp ${stampFile(name).name}", stampFile(name).exists())
        }
        // The baked graphs are ~1 MB of structure that resolve every initializer through this file.
        // Without it they do not load, so its presence is part of "the cache exists".
        assertTrue("shared weight blob must survive — the baked graphs point at it", blobFile().exists())
    }

    /** The warm launch removes the filesDir source copy and the superseded `.ort` cache. */
    private fun assertNoFilesDirDuplication() {
        for (name in sources) {
            val base = name.removeSuffix(".onnx")
            for (obsolete in listOf(name, "$base.ort", "$base.ort.stamp")) {
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
        blobFile().delete() // a true cold start re-extracts the blob too
    }

    private companion object {
        const val TAG = "BB_OPTCACHE"
    }
}
