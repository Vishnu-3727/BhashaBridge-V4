package com.bhashabridge.app.mt

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.bench.SystemStats
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Queue item Q13: why are the `.ort` models not memory-mapped?
 *
 * §3.25 measured zero mappings under the app's data directories and ~551 MB of weights in the native
 * heap, despite `session.use_memory_mapped_ort_model=1` — a real key in ORT 1.27.0 — and a
 * path-based session build. The leading explanation was that MLAS **pre-packs** the int8 weights at
 * session init, copying the mapped bytes into kernel-friendly buffers and dropping the mapping.
 *
 * Two things settle it:
 *
 *  1. **Sample `/proc/self/maps` *during* `createSession`.** A post-load sample cannot tell "never
 *     mapped" from "mapped, then unmapped". A watcher thread polling while the session loads can.
 *  2. **A/B `session.disable_prepacking`.** If prepacking is the cause, turning it off should leave
 *     the mappings in place and the heap smaller.
 *
 * Inference cost is measured too, because the answer only matters if it is affordable: prepacking
 * exists to make the int8 kernels fast, and trading ~500 MB of resident heap for a slow decode is
 * not a trade this app can make.
 *
 * Logged under `BB.Q13`.
 */
@RunWith(AndroidJUnit4::class)
class MmapPrepackTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun prepackingVersusMappedWeights() {
        arm("prepack_on", ExecutionPolicy.current)
        arm("prepack_off", ExecutionPolicy.current.copy(disablePrepacking = true))
    }

    private fun arm(label: String, tune: OrtTuning) {
        val peakMapped = AtomicLong(0)
        val watching = AtomicBoolean(true)
        // Polls hard: the mapping may exist only for the length of the load, and on this device the
        // three sessions build in ~6 s, so a 50 ms poll gives ~120 samples to catch it in.
        val watcher = Thread {
            while (watching.get()) {
                val mapped = mappedModelBytes().first
                peakMapped.updateAndGet { maxOf(it, mapped) }
                Thread.sleep(50)
            }
        }.apply { isDaemon = true; start() }

        val loadStart = System.nanoTime()
        val engine = MtEngine(app, Direction.EN_TO_HI, tune = tune)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        watching.set(false)
        watcher.join(1_000)

        val afterLoad = SystemStats.capture(app, label)
        val mappedAfter = mappedModelBytes()

        // Warm, then time. n=10 is enough to separate "prepacking matters a lot" from "it does not";
        // this is a mechanism question, not a tuning sweep.
        repeat(3) { engine.translate(SENTENCE) }
        val latencies = (0 until 10).map {
            val t = System.nanoTime()
            engine.translate(SENTENCE)
            (System.nanoTime() - t) / 1_000_000
        }
        val stats = Stats.of(latencies)

        Log.i(
            TAG,
            "REPORT $label load_ms=$loadMs" +
                " peak_mapped_kb=${peakMapped.get() / 1024}" +
                " mapped_after_kb=${mappedAfter.first / 1024} mappings_after=${mappedAfter.second}" +
                " heap_alloc_kb=${Debug.getNativeHeapAllocatedSize() / 1024}" +
                " heap_size_kb=${Debug.getNativeHeapSize() / 1024}" +
                " pss_kb=${afterLoad.totalPssKb}" +
                " translate_median_ms=${stats.median} p95=${stats.p95}",
        )

        engine.release()
        Thread.sleep(12_000) // §3.25: the allocator returns pages ~10 s after release
    }

    private fun mappedModelBytes(): Pair<Long, Int> = runCatching {
        var bytes = 0L
        var count = 0
        File("/proc/self/maps").forEachLine { line ->
            if (line.contains("/com.bhashabridge") &&
                (line.contains(".ort") || line.contains(".onnx") || line.contains(".bin"))
            ) {
                val range = line.substringBefore(' ')
                val start = range.substringBefore('-').toLong(16)
                val end = range.substringAfter('-').substringBefore(' ').toLong(16)
                bytes += end - start
                count++
            }
        }
        bytes to count
    }.getOrDefault(0L to 0)

    private companion object {
        const val TAG = "BB.Q13"
        const val SENTENCE = "The weather is very nice today and I want to go outside."
    }
}
