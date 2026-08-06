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

/**
 * Queue item Q14: can the model weights stay **file-backed** instead of sitting in anonymous heap?
 *
 * §3.26 established that ORT maps 451 MB while building the sessions and then drops the mapping,
 * leaving ~559 MB of anonymous native heap — and that prepacking is not the cause. The remaining
 * mechanism is initializer copying on the path-based load. This A/Bs the alternative: map the `.ort`
 * with `FileChannel.map` and load through `createSession(ByteBuffer, …)` with
 * `use_ort_model_bytes_directly` + `use_ort_model_bytes_for_initializers`.
 *
 * Why it would matter: anonymous pages have to be swapped or killed under memory pressure, while
 * clean file-backed pages can be dropped and re-read. Same bytes resident, very different behaviour
 * when the low-memory killer is choosing a victim.
 *
 * Measured per arm: mappings that **survive** the load, native heap, PSS, load time, and translate
 * latency — because file-backed weights are only worth having if inference does not pay for them.
 *
 * Logged under `BB.Q14`.
 */
@RunWith(AndroidJUnit4::class)
class MappedInitializersTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun mappedInitializersVersusPathLoad() {
        arm("path_load", ExecutionPolicy.current)
        arm("mapped_initializers", ExecutionPolicy.current.copy(mappedInitializers = true))
    }

    /**
     * The same A/B with the arms reversed, run in its own process.
     *
     * The first arm to run reads 451 MB of `.ort` off storage; the second finds it in the page
     * cache. That confound is worth more than the difference being measured — the forward run showed
     * load time 6,981 → 2,703 ms, which is exactly what a warm page cache looks like. Only the
     * numbers that survive **both** orderings mean anything.
     */
    @Test
    fun reversedOrderToSeparateMethodFromPageCache() {
        arm("rev_mapped_initializers", ExecutionPolicy.current.copy(mappedInitializers = true))
        arm("rev_path_load", ExecutionPolicy.current)
    }

    private fun arm(label: String, tune: OrtTuning) {
        val loadStart = System.nanoTime()
        val engine = MtEngine(app, Direction.EN_TO_HI, tune = tune)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        val mapped = mappedModelBytes()
        val stats = SystemStats.capture(app, label)

        repeat(3) { engine.translate(SENTENCE) }
        val latencies = (0 until 10).map {
            val t = System.nanoTime()
            engine.translate(SENTENCE)
            (System.nanoTime() - t) / 1_000_000
        }
        val timing = Stats.of(latencies)

        Log.i(
            TAG,
            "REPORT $label load_ms=$loadMs" +
                " mapped_after_kb=${mapped.first / 1024} mappings=${mapped.second}" +
                " heap_alloc_kb=${Debug.getNativeHeapAllocatedSize() / 1024}" +
                " heap_size_kb=${Debug.getNativeHeapSize() / 1024}" +
                " pss_kb=${stats.totalPssKb} native_pss_kb=${stats.nativePssKb}" +
                " translate_median_ms=${timing.median} p95=${timing.p95}",
        )
        // Output parity is the gate: a load path that changes the translation is not an optimization.
        Log.i(TAG, "REPORT $label output=${engine.translate(SENTENCE)}")

        engine.release()
        Thread.sleep(12_000) // §3.25: the allocator hands pages back ~10 s after release
    }

    private fun mappedModelBytes(): Pair<Long, Int> = runCatching {
        var bytes = 0L
        var count = 0
        File("/proc/self/maps").forEachLine { line ->
            if (line.contains("/com.bhashabridge") && (line.contains(".ort") || line.contains(".onnx"))) {
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
        const val TAG = "BB.Q14"
        const val SENTENCE = "The weather is very nice today and I want to go outside."
    }
}
