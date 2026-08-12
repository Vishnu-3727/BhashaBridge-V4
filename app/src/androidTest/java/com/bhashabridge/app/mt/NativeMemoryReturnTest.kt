package com.bhashabridge.app.mt

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.SystemStats
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Queue item Q11: after `release()`, PSS stays near its peak. Why?
 *
 * `EngineFootprintTest` measured it — two engines closed, PSS 1,491 MB → 1,441 MB after a GC and
 * still 1,441 MB thirteen seconds later, against 51 MB idle — but not the cause. Three explanations
 * fit that number and they have very different consequences:
 *
 *  1. **ORT is not freeing.** `close()` leaves native allocations behind → a real leak, and the
 *     eviction in `BhashaBridgeApp` buys nothing at all.
 *  2. **The allocator is retaining freed spans.** scudo keeps the pages mapped for reuse → the
 *     memory is genuinely free to the *process*, and eviction lets the next engine reuse it, which
 *     is the weaker claim §3.24b already had to fall back on.
 *  3. **The `.ort` files are still mapped.** Phase 2B loads them with
 *     `use_memory_mapped_ort_model=1`, so their resident pages count in PSS while mapped, and only
 *     leave when the mapping does.
 *
 * These are separable without JNI:
 *
 *  - `Debug.getNativeHeapAllocatedSize()` is what malloc currently owes the app. If it drops on
 *     release, ORT freed (2 or 3, not 1).
 *  - `Debug.getNativeHeapSize()` is what the allocator holds from the OS. If that stays high while
 *     allocated drops, the allocator is retaining (2).
 *  - `/proc/self/maps` says whether the `.ort` files are still mapped, and how much (3).
 *
 * Logged under `BB.Q11`.
 */
@RunWith(AndroidJUnit4::class)
class NativeMemoryReturnTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun whereTheMemoryGoesAfterRelease() {
        sample("idle")

        val engine = MtEngine(app, Direction.EN_TO_HI)
        sample("engine_built")
        dumpAppMappings()

        engine.translate("The weather is very nice today and I want to go outside.")
        sample("after_translate")

        engine.release()
        sample("released_immediate")

        System.gc()
        Thread.sleep(2_000)
        sample("released_after_gc")

        Thread.sleep(8_000)
        sample("released_after_10s")

        // A second engine, to answer the question the numbers above cannot: whether whatever is
        // being held gets REUSED. If the allocator is retaining freed spans (explanation 2), this
        // build should cost far less new memory than the first one did.
        val second = MtEngine(app, Direction.EN_TO_HI)
        sample("second_engine_built")
        second.release()
        sample("second_released")
    }

    private fun sample(label: String) {
        val stats = SystemStats.capture(app, label)
        val mapped = mappedModelBytes()
        Log.i(
            TAG,
            "REPORT $label" +
                " pss_kb=${stats.totalPssKb}" +
                " native_pss_kb=${stats.nativePssKb}" +
                " heap_size_kb=${Debug.getNativeHeapSize() / 1024}" +
                " heap_alloc_kb=${Debug.getNativeHeapAllocatedSize() / 1024}" +
                " heap_free_kb=${Debug.getNativeHeapFreeSize() / 1024}" +
                " ort_mapped_kb=${mapped.first / 1024}" +
                " ort_mappings=${mapped.second}",
        )
    }

    /**
     * Bytes of address space currently mapped from `.ort` / `.onnx` files, and how many mappings.
     *
     * Address space, not resident pages — a mapping that is still listed is a mapping ORT has not
     * released, which is the question. Resident-vs-mapped is what `native_pss_kb` covers.
     */
    private fun mappedModelBytes(): Pair<Long, Int> = runCatching {
        var bytes = 0L
        var count = 0
        File("/proc/self/maps").forEachLine { line ->
            if (line.endsWith(".ort") || line.endsWith(".onnx") || line.endsWith(".bin")) {
                val range = line.substringBefore(' ')
                val start = range.substringBefore('-').toLong(16)
                val end = range.substringAfter('-').substringBefore(' ').toLong(16)
                bytes += end - start
                count++
            }
        }
        bytes to count
    }.getOrDefault(0L to 0)

    /**
     * Raw evidence for the mapping question, so "no `.ort` mappings" is a reading of `/proc/self/maps`
     * rather than a reading of my own matcher. Prints every mapping under the app's own directories.
     */
    private fun dumpAppMappings() = runCatching {
        var shown = 0
        File("/proc/self/maps").forEachLine { line ->
            if (shown < 12 && (line.contains("/data/user/0/com.bhashabridge") ||
                    line.contains("/data/data/com.bhashabridge"))
            ) {
                Log.i(TAG, "MAPS $line")
                shown++
            }
        }
        if (shown == 0) Log.i(TAG, "MAPS none under the app's data directories")
    }

    private companion object {
        const val TAG = "BB.Q11"
    }
}
