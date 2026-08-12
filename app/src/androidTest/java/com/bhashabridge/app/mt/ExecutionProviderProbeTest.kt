package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Q7: can a detected CPU capability select a **different kernel implementation**, rather than just a
 * different amount of the same one?
 *
 * The gap this probes is real and was written down before it was measured: `CpuCapabilities` detects
 * `dotprod` / `i8mm` / `sve2` / `sme`, and `ExecutionPolicy` then uses them for thread count, logging
 * and one KleidiAI predicate — nothing that picks a different GEMM. INT8 acceleration comes from
 * MLAS dispatching on HWCAP at runtime, below anything this app can configure.
 *
 * Two mechanisms could close that, and both are measured here against the shipping CPU path:
 *
 *  - **A different execution provider** ([ExecutionBackend]) — NNAPI or XNNPACK, if the shipped AAR
 *    contains them. Availability is a build property of the AAR, so registration may simply throw;
 *    that is recorded as a result rather than treated as a failure.
 *  - **A capability-gated MLAS switch** — `mlas.enable_gemm_fastmath_arm64_bfloat16`, which is only
 *    meaningful on a CPU with bf16, and `mlas.disable_kleidiai`, which is the existing generic-vs-Arm
 *    instrument.
 *
 * Every arm runs a **real translation** and its output is compared against the CPU arm's. An EP that
 * quietly changes the translation is a correctness failure, not a slower option, and on a quantized
 * transformer that is the likely outcome rather than a remote one — which is why parity is reported
 * per arm and not assumed.
 *
 * Arms use `optCache = false` deliberately: the bake writes an artifact specific to whatever
 * partitioned the graph, so a provider comparison must not go through it.
 *
 * Logged under `BB.Q7`.
 */
@RunWith(AndroidJUnit4::class)
class ExecutionProviderProbeTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun whichProvidersExistAndDoAnyBeatMlas() {
        val caps = ExecutionPolicy.capabilities
        Log.i(TAG, "CPU ${caps.describe()}")
        Log.i(TAG, "PROVIDERS_COMPILED_IN ${OrtEnvironment.getAvailableProviders()}")
        Log.i(TAG, "POLICY ${ExecutionPolicy.current.name}")

        // Raw source graphs, no bake — see the class KDoc.
        val base = ExecutionPolicy.current.copy(optCache = false)

        val arms = listOf(
            Arm("cpu_mlas", base),
            Arm("nnapi", base.copy(backend = ExecutionBackend.NNAPI)),
            Arm("xnnpack", base.copy(backend = ExecutionBackend.XNNPACK)),
            // Capability-gated by construction: bf16 fastmath GEMM is only a different kernel on a
            // CPU that has bf16. On one that does not, this must be a no-op — which is itself the
            // check that the flag is doing what its name says.
            Arm("mlas_bf16_fastmath", base.copy(gemmFastMathBf16 = true)),
            // The existing generic-vs-Arm instrument, for scale: this is what a real kernel swap
            // looks like in the numbers on a part where it dispatches.
            Arm("kleidiai_off", base.copy(disableKleidiAi = true)),
        )

        // Rotated, because the first pass at this ran the arms in order and they got monotonically
        // faster — 662 ms for the first arm down to 642 ms for the last. At a 1-3% spread between the
        // CPU-side arms that ordering effect is larger than the effect being measured (§0 rule 3).
        var reference: String? = null
        val medians = LinkedHashMap<String, MutableList<Double>>()
        repeat(ROUNDS) { round ->
            val order = arms.drop(round % arms.size) + arms.take(round % arms.size)
            for (arm in order) {
                val result = runCatching { measure(arm) }.getOrElse { e ->
                    // Unavailable or rejected: the answer to "can we select this", not a test failure.
                    Log.i(TAG, "ARM ${arm.label} UNAVAILABLE ${e::class.java.simpleName}: ${e.message?.take(180)}")
                    null
                } ?: continue

                if (arm.label == "cpu_mlas" && reference == null) reference = result.output
                val parity = when {
                    reference == null -> "NO_REF"
                    result.output == reference -> "EXACT"
                    else -> "DIFFERS"
                }
                medians.getOrPut(arm.label) { mutableListOf() } += result.timing.median
                Log.i(
                    TAG,
                    "ROUND ${round + 1} ${arm.label} load_ms=${result.loadMs} ${result.timing.toJson()} " +
                        "parity=$parity out='${result.output.take(46)}'",
                )
            }
        }
        medians.forEach { (label, m) -> Log.i(TAG, "ARM $label medians=$m") }
    }

    /**
     * Builds one session per backend with ORT's own logging at INFO, so its `Node(s) placed on [...]`
     * line lands in logcat.
     *
     * This is the difference between "XNNPACK tied MLAS" and "XNNPACK claimed nothing and MLAS ran
     * anyway" — identical timings are the expected result of the second, so the timings alone cannot
     * tell them apart. Separate test because the logging is noisy and would swamp the benchmark.
     */
    @Test
    fun whichNodesEachProviderActuallyClaims() {
        for (backend in ExecutionBackend.entries) {
            // ORT's own INFO log is gated by the *environment* severity, and the environment is a
            // process-wide singleton already created by the time a test runs — so the profiler is the
            // instrument that actually answers this. Its trace names the provider that executed each
            // node, which is exactly the question.
            val dir = java.io.File(app.getExternalFilesDir(null), "ep-$backend").apply {
                deleteRecursively(); mkdirs()
            }
            val tune = ExecutionPolicy.current.copy(
                optCache = false,
                backend = backend,
                profileDir = dir.absolutePath,
            )
            Log.i(TAG, "PLACEMENT_BEGIN $backend")
            runCatching {
                val engine = MtEngine(app, Direction.EN_TO_HI, tune = tune)
                engine.translate(SENTENCE)
                engine.endProfiling().forEach { Log.i(TAG, "PLACEMENT_TRACE $backend $it") }
                engine.release()
            }.onFailure { Log.i(TAG, "PLACEMENT $backend UNAVAILABLE ${it::class.java.simpleName}") }
            Log.i(TAG, "PLACEMENT_END $backend")
            Thread.sleep(SETTLE_MS)
        }
    }

    private fun measure(arm: Arm): Result {
        val t0 = System.nanoTime()
        val engine = MtEngine(app, Direction.EN_TO_HI, tune = arm.tune)
        val loadMs = (System.nanoTime() - t0) / 1_000_000
        return try {
            repeat(3) { engine.translate(SENTENCE) }
            val samples = (0 until RUNS).map {
                val t = System.nanoTime()
                engine.translate(SENTENCE)
                (System.nanoTime() - t) / 1_000_000
            }
            Result(loadMs, Stats.of(samples), engine.translate(SENTENCE))
        } finally {
            engine.release()
            Thread.sleep(SETTLE_MS)
        }
    }

    private class Arm(val label: String, val tune: OrtTuning)
    private class Result(val loadMs: Long, val timing: Stats, val output: String)

    private companion object {
        const val TAG = "BB.Q7"
        const val ROUNDS = 3
        const val RUNS = 10
        const val SETTLE_MS = 2_000L
        const val SENTENCE = "The weather is very nice today and I want to go outside."
    }
}
