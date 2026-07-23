package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 * Phase 3 A/B benchmark for `session.intra_op_thread_affinities`. Three tunings off the production
 * policy, differing only in affinity:
 *  - OFF   : no affinity (pre-Phase-3 scheduling)
 *  - ON    : ORT workers pinned to the performance cluster (the shipped policy)
 *  - LITTLE: workers pinned to the efficiency cluster — a deliberately wrong pin
 *
 * OFF vs ON are interleaved per iteration with the order swapped each round, so thermal drift cancels
 * (counterbalanced). LITTLE is the experimental check that the CPU ids bind as intended: if pinning to
 * the little cores is clearly slower than ON, the 0-based ids map to real cores and affinity is live.
 *
 * The test asserts only correctness — every arm must produce identical output, and every engine must
 * build (a bad affinity string would throw in ORT at session creation). Latency/variance are logged
 * for interpretation; a benchmark must not fail on thermal noise.
 */
@RunWith(AndroidJUnit4::class)
class AffinityBenchmarkTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val probe = "The weather is very nice today and I want to go outside."
    private val parityInputs = listOf("Water.", "Hello, how are you?", probe)
    private val warmup = 5
    private val runs = 30

    @Test
    fun affinityOnOffLittle() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current // production: perf-pinned affinity ON
        val threads = base.intraThreads ?: 1
        val littleAffinity = if (caps.efficiencyCoreIds.isNotEmpty() && threads > 1) {
            // ORT ids are 1-based, same encoding as ExecutionPolicy.affinityString.
            val group = caps.efficiencyCoreIds.joinToString(",") { (it + 1).toString() }
            List(threads - 1) { group }.joinToString(";")
        } else null

        Log.i(TAG, "TOPOLOGY ${caps.describe()}")
        Log.i(TAG, "AFFINITY threads=$threads perfIds=${caps.performanceCoreIds} effIds=${caps.efficiencyCoreIds}")
        Log.i(TAG, "AFFINITY_STRING on='${base.intraOpAffinities}' little='$littleAffinity'")
        Log.i(TAG, "TEMP_START ${batteryTemp()}")

        val off = base.copy(name = "affinity-off", intraOpAffinities = null)
        val on = base.copy(name = "affinity-on")

        // Two engines resident; ORT accepting the affinity string at createSession == "affinity applied".
        val engOff = MtEngine(context, Direction.EN_TO_HI, GreedyDecoder(), off)
        val engOn = MtEngine(context, Direction.EN_TO_HI, GreedyDecoder(), on)
        val offMs = ArrayList<Long>(runs)
        val onMs = ArrayList<Long>(runs)
        val expected: List<String>
        try {
            repeat(warmup) { engOff.translate(probe); engOn.translate(probe) }
            for (i in 0 until runs) {
                if (i % 2 == 0) { offMs += timeMs { engOff.translate(probe) }; onMs += timeMs { engOn.translate(probe) } }
                else { onMs += timeMs { engOn.translate(probe) }; offMs += timeMs { engOff.translate(probe) } }
            }
            // Correctness: affinity must not change any output. Capture the reference for the LITTLE arm.
            expected = parityInputs.map { engOn.translate(it) }
            for (i in parityInputs.indices) assertEquals("affinity changed output for '${parityInputs[i]}'", expected[i], engOff.translate(parityInputs[i]))
        } finally {
            engOff.release(); engOn.release()
        }
        report("OFF", offMs)
        report("ON", onMs)
        Log.i(TAG, "TEMP_MID ${batteryTemp()}")

        // Binding validation: pin to the little cluster, expect clearly worse latency.
        val littleMs = ArrayList<Long>(runs)
        if (littleAffinity != null) {
            val engLittle = MtEngine(context, Direction.EN_TO_HI, GreedyDecoder(), base.copy(name = "affinity-little", intraOpAffinities = littleAffinity))
            try {
                repeat(warmup) { engLittle.translate(probe) }
                repeat(runs) { littleMs += timeMs { engLittle.translate(probe) } }
                for (i in parityInputs.indices) assertEquals("little-pin changed output for '${parityInputs[i]}'", expected[i], engLittle.translate(parityInputs[i]))
            } finally {
                engLittle.release()
            }
            report("LITTLE", littleMs)
        }
        Log.i(TAG, "TEMP_END ${batteryTemp()}")

        assertTrue("no latency samples collected", offMs.isNotEmpty() && onMs.isNotEmpty())
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
    }

    private fun report(label: String, xs: List<Long>) {
        val s = xs.sorted()
        val median = s[s.size / 2]
        val p95 = s[minOf(s.size - 1, (s.size * 95 + 99) / 100 - 1)]
        val mean = s.average()
        val stdev = sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size)
        Log.i(TAG, "RESULT $label n=${s.size} median=${median}ms p95=${p95}ms stdev=${"%.1f".format(stdev)}ms min=${s.first()}ms max=${s.last()}ms")
    }

    private fun batteryTemp(): String =
        runCatching {
            java.io.File("/sys/class/power_supply/battery/temp").readText().trim()
        }.getOrDefault("n/a")

    private companion object {
        const val TAG = "BB_AFFINITY"
    }
}
