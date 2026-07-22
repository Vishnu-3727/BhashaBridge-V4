package com.bhashabridge.app.mt

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 12 benchmark for HI→EN, deliberately identical in shape to [MtBenchmarkTest] so the two
 * directions can be compared without a caveat: same warmup, same run count, same `BB_BENCH` markers,
 * same offline parser (`model_pipeline/bench_parse.py`). The three sentences are the direct Hindi
 * translations of that test's three English ones, so the comparison holds sentence length roughly
 * constant instead of comparing an easy sentence against a hard one.
 *
 * The final measurement is the one the app actually incurs after a swap: **both** engines resident,
 * which is what `BhashaBridgeApp` holds once a user has translated in both directions.
 */
@RunWith(AndroidJUnit4::class)
class HiEnBenchmarkTest {

    private val sentences = listOf(
        "पानी।",
        "नमस्ते, आप कैसे हैं?",
        "आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ।",
    )
    private val warmup = 3
    private val runs = 30

    @Test
    fun benchmarkHiEnGreedy() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Log.i(TAG, "CPU " + CpuCapabilities.detect().describe())
        val policy = ExecutionPolicy.current
        Log.i(TAG, "POLICY ${policy.name} intra=${policy.intraThreads} arena=${policy.cpuArena}")

        val engine = MtEngine(ctx, Direction.HI_TO_EN)
        try {
            repeat(warmup) { sentences.forEach { engine.translate(it) } } // discarded
            Log.i(TAG, "WARMUP_DONE")

            sentences.forEachIndexed { i, s ->
                Log.i(TAG, "BENCH_SENTENCE i=$i n=$runs text=\"$s\"")
                var out = ""
                repeat(runs) { out = engine.translate(s) }
                Log.i(TAG, "BENCH_OUTPUT i=$i out=\"$out\"")
            }
            Log.i(TAG, "BENCH_MEM " + pss("hi_en_only"))

            // Both directions resident — the real cost of a bidirectional session. The marker opens
            // a fresh bucket: without it the parser attributes the two probe translations below to
            // the last benchmarked sentence and quietly corrupts its statistics.
            Log.i(TAG, "BENCH_SENTENCE i=3 n=2 text=\"memory probe (not a benchmark)\"")
            val enHi = MtEngine(ctx, Direction.EN_TO_HI)
            try {
                enHi.translate("Water.")
                engine.translate("पानी।")
                Log.i(TAG, "BENCH_MEM " + pss("both_directions"))
            } finally {
                enHi.release()
            }
        } finally {
            engine.release()
        }
    }

    private fun pss(label: String): String {
        val mi = Debug.MemoryInfo()
        Debug.getMemoryInfo(mi)
        return "state=$label totalPss=${mi.totalPss} nativePss=${mi.nativePss} dalvikPss=${mi.dalvikPss}"
    }

    private companion object {
        const val TAG = "BB_BENCH"
    }
}
