package com.bhashabridge.app.mt

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 6D benchmark harness. Drives EN→HI greedy translation many times so `Metrics` emits one
 * `BB.Bench` JSON line per call; the surrounding `BB_BENCH` markers let the offline parser attribute
 * each line to a sentence. The SAME test runs against both runtimes (int8 uncached and int8 cached) —
 * it only calls `translate` / `release`, which both expose, so the comparison is like-for-like:
 * identical sentences, tokenizer, decoder, device, and build config.
 *
 * Not a modification of the benchmark subsystem — it only consumes the frozen `Metrics` output.
 */
@RunWith(AndroidJUnit4::class)
class MtBenchmarkTest {

    private val sentences = listOf(
        "Water.",
        "Hello, how are you?",
        "The weather is very nice today and I want to go outside.",
    )
    private val warmup = 3
    private val runs = 30

    @Test
    fun benchmarkEnHiGreedy() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Surface the detected CPU + the policy it drives, so the platform report has real evidence.
        Log.i(TAG, "CPU " + CpuCapabilities.detect().describe())
        val policy = ExecutionPolicy.current
        Log.i(TAG, "POLICY ${policy.name} intra=${policy.intraThreads} arena=${policy.cpuArena}")
        val engine = MtEngine(ctx, Direction.EN_TO_HI)
        try {
            repeat(warmup) { sentences.forEach { engine.translate(it) } } // discarded
            Log.i(TAG, "WARMUP_DONE")

            sentences.forEachIndexed { i, s ->
                Log.i(TAG, "BENCH_SENTENCE i=$i n=$runs text=\"$s\"")
                var out = ""
                repeat(runs) { out = engine.translate(s) } // each call logs one BB.Bench JSON line
                Log.i(TAG, "BENCH_OUTPUT i=$i out=\"$out\"")
            }

            // Process footprint after all sessions are loaded and the runs are done.
            val mi = Debug.MemoryInfo()
            Debug.getMemoryInfo(mi)
            Log.i(TAG, "BENCH_MEM totalPss=${mi.totalPss} nativePss=${mi.nativePss} dalvikPss=${mi.dalvikPss}")
        } finally {
            engine.release()
        }
    }

    private companion object {
        const val TAG = "BB_BENCH"
    }
}
