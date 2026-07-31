package com.bhashabridge.app.mt

import android.content.Context
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
 * Production-path thread + affinity A/B.
 *
 * [MtTuningSweepTest] answers the same question on the *non-production* load path: it leaves
 * `optCache` off so it can vary `optLevel`, so every config there loads the source `.onnx` under
 * ALL_OPT. Production loads a baked `.ort` under NO_OPT with mmap, and `OnnxModels.loadOptions()`
 * builds those options from `tune.toOptions()` — so `intraThreads`, `cpuArena` and affinity all
 * still apply, but the surrounding graph and allocator behaviour differ. This test therefore keeps
 * `ExecutionPolicy.current` intact (cache on) and varies one knob at a time on top of it.
 *
 * **Drift handling differs from [MtTuningSweepTest] deliberately.** That test runs each config as one
 * contiguous block, so a config's position in the list contaminates its result — on the SM-S948B the
 * baseline bookends showed +13.4% drift across a 97 s run, roughly +1.2% per position, which is the
 * same order as the effect being measured. Here every config is measured in every round, with the
 * order rotated each round, so position averages out instead of accumulating. Report the median of
 * the pooled samples.
 *
 * Asserts correctness only (all arms must produce identical output). Latency is logged, never
 * asserted — a benchmark must not fail on thermal noise.
 */
@RunWith(AndroidJUnit4::class)
class ProductionThreadSweepTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val probe = "The weather is very nice today and I want to go outside."
    private val short = "Water."
    private val rounds = 3
    private val warmup = 3
    private val runsPerRound = 15

    @Test
    fun sweepProductionPath() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        val primeIds = topFrequencyCoreIds()

        Log.i(TAG, "TOPOLOGY ${caps.describe()}")
        Log.i(TAG, "PRODUCTION base=${base.name} intra=${base.intraThreads} arena=${base.cpuArena} " +
            "optCache=${base.optCache} affinity=${base.intraOpAffinities ?: "OFF"}")
        Log.i(TAG, "PRIME_CORES $primeIds (top cpufreq tier)")
        Log.i(TAG, "TEMP_START ${batteryTemp()}")

        val configs = buildList {
            add("intra1" to base.copy(name = "intra1", intraThreads = 1, intraOpAffinities = null))
            add("intra2" to base.copy(name = "intra2", intraThreads = 2, intraOpAffinities = null))
            add("intra4_SHIPPING" to base.copy(name = "intra4", intraThreads = 4, intraOpAffinities = null))
            add("intra6" to base.copy(name = "intra6", intraThreads = 6, intraOpAffinities = null))
            add("intra8" to base.copy(name = "intra8", intraThreads = 8, intraOpAffinities = null))
            // Single-variable change off shipping: arena back on. Phase 7 turned it off on the
            // SM-M315F for a memory win; the non-production sweep says it costs ~12% here.
            add("intra4_arenaOn" to base.copy(name = "intra4_arenaOn", intraThreads = 4, cpuArena = true, intraOpAffinities = null))
            // Pin 2 workers to the top-frequency (prime) tier, which sat idle at 883 MHz during the
            // entry #9 benchmark. ORT ids are 1-based and it needs exactly intraThreads-1 groups.
            if (primeIds.isNotEmpty()) {
                val group = primeIds.joinToString(",") { (it + 1).toString() }
                add("intra2_primePin" to base.copy(name = "intra2_primePin", intraThreads = 2, intraOpAffinities = group))
            }
            // KleidiAI off: the ONLY way to attribute the SME contribution. simpleperf shows the hot
            // int8 GEMM on this device is KleidiAI's SME `smopa` kernel; disabling it forces MLAS's
            // own kernels, so the delta is what SME+KleidiAI is worth. Measured at both the shipping
            // thread count and the fastest one so the two variables do not confound.
            add("intra4_noKleidiAI" to base.copy(name = "intra4_noKleidiAI", intraThreads = 4, disableKleidiAi = true, intraOpAffinities = null))
            add("intra1_noKleidiAI" to base.copy(name = "intra1_noKleidiAI", intraThreads = 1, disableKleidiAi = true, intraOpAffinities = null))
        }

        val longSamples = LinkedHashMap<String, MutableList<Long>>()
        val shortSamples = LinkedHashMap<String, MutableList<Long>>()
        configs.forEach { (label, _) ->
            longSamples[label] = ArrayList(); shortSamples[label] = ArrayList()
        }

        var reference: String? = null
        var referenceShort: String? = null

        for (round in 0 until rounds) {
            // Rotate so no config keeps the coolest (or hottest) slot across rounds.
            val order = configs.drop(round % configs.size) + configs.take(round % configs.size)
            for ((label, tune) in order) {
                val engine = MtEngine(context, Direction.EN_TO_HI, GreedyDecoder(), tune)
                try {
                    repeat(warmup) { engine.translate(probe) }
                    repeat(runsPerRound) {
                        longSamples.getValue(label) += timeMs { engine.translate(probe) }
                        shortSamples.getValue(label) += timeMs { engine.translate(short) }
                    }
                    val out = engine.translate(probe)
                    val outShort = engine.translate(short)
                    if (reference == null) { reference = out; referenceShort = outShort }
                    assertEquals("config $label changed long output", reference, out)
                    assertEquals("config $label changed short output", referenceShort, outShort)
                } finally {
                    engine.release()
                }
            }
            Log.i(TAG, "ROUND ${round + 1}/$rounds done temp=${batteryTemp()}")
        }

        Log.i(TAG, "TEMP_END ${batteryTemp()}")
        configs.forEach { (label, _) ->
            report("LONG", label, longSamples.getValue(label))
            report("SHORT", label, shortSamples.getValue(label))
        }
        assertTrue("no samples collected", longSamples.values.all { it.isNotEmpty() })
    }

    /** Logical cpu ids at the highest `cpuinfo_max_freq` — the prime tier on a multi-tier CPU. */
    private fun topFrequencyCoreIds(): List<Int> {
        val cores = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until cores).map { c ->
            runCatching {
                File("/sys/devices/system/cpu/cpu$c/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
            }.getOrDefault(0L)
        }
        val top = freqs.maxOrNull() ?: 0L
        // A single-tier CPU has nothing distinct to pin; report empty so the arm is skipped.
        if (top <= 0L || freqs.all { it == top }) return emptyList()
        return freqs.indices.filter { freqs[it] == top }
    }

    private inline fun timeMs(block: () -> Unit): Long {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
    }

    private fun report(kind: String, label: String, xs: List<Long>) {
        val s = xs.sorted()
        val median = s[s.size / 2]
        val p95 = s[minOf(s.size - 1, (s.size * 95 + 99) / 100 - 1)]
        val mean = s.average()
        val stdev = kotlin.math.sqrt(s.sumOf { (it - mean) * (it - mean) } / s.size)
        Log.i(TAG, "RESULT $kind $label n=${s.size} median=${median}ms p95=${p95}ms " +
            "stdev=${"%.1f".format(stdev)}ms min=${s.first()}ms max=${s.last()}ms")
    }

    private fun batteryTemp(): String =
        runCatching { File("/sys/class/power_supply/battery/temp").readText().trim() }.getOrDefault("n/a")

    private companion object {
        const val TAG = "BB_PROD_SWEEP"
    }
}
