package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.bench.Stats
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Q18: where do the ~2.15 s of `sessions:parallel` actually go on the **shipping warm path**, and
 * does any reachable `SessionOptions` knob shorten it?
 *
 * The measured cold-launch shape (SM-M315F, 2026-08-12, three cold launches, `.ort` cache warm) is
 * `create_us` encoder ≈ 0.9–1.1 s against decoder_init ≈ decoder_step ≈ 2.07–2.21 s, all three
 * concurrent — so the critical path is **one decoder-sized load**, ~200 MB of `.ort`, and the encoder
 * is free. Anything that helps has to help *that*.
 *
 * Every arm loads the same three EN→HI `.ort` files that production loads, with production's own
 * options as the baseline, and closes the sessions immediately: this measures **construction only**,
 * never inference. An arm that wins here is a candidate, not a decision — parity and translate
 * latency are `BenchmarkSuiteTest`'s job.
 *
 * **Ordering is the confound that matters**, not noise (§3.27: a "−61% load time" turned out to be
 * the page cache). Arms are therefore rotated by round, and the round-by-round numbers are logged
 * next to the medians so a monotone drift is visible instead of averaged away.
 *
 * Logged under `BB.Q18`.
 */
@RunWith(AndroidJUnit4::class)
class OrtLoadProbeTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    /** The production trio, in the order `OnnxModels` submits them. */
    private val models
        get() = listOf("encoder_int8.ort", "decoder_init_int8.ort", "decoder_step_int8.ort")
            .map { File(app.filesDir, it) }

    @Test
    fun probeLoadArms() {
        assumeTrue(
            "no baked .ort in filesDir — launch the app once so the production cache exists",
            models.all { it.exists() },
        )
        val bytes = models.sumOf { it.length() }
        Log.i(TAG, "CONFIG policy=${ExecutionPolicy.current.name} bytes=$bytes rounds=$ROUNDS")

        // The I/O floor for the same bytes, once, before any arm: if a decoder-sized read already
        // costs most of the load, no session knob can be the answer.
        val readMs = measure { models.forEach { f -> f.inputStream().use { it.readIntoVoid() } } }
        Log.i(TAG, "IO_FLOOR sequential_read_ms=$readMs")

        val arms = listOf(
            // Production: path load, NO_OPT, mmapped .ort.
            Arm("baseline") { label -> loadParallel(label) { production() } },
            // Q14's knob, unmeasured for load time because §3.27's design could not separate it from
            // the page cache. Skips the initializer copy into the session allocator.
            Arm("mapped_init") { label -> loadParallel(label) { null } },
            // Diagnostic only, never shippable (§3.26: inference 4.6× slower). Tells us how much of
            // the load is MLAS repacking the int8 weights.
            Arm("no_prepack") { label ->
                loadParallel(label) { production().apply { addConfigEntry("session.disable_prepacking", "1") } }
            },
            // Initializers straight from the device allocator instead of through the arena.
            Arm("dev_alloc") { label ->
                loadParallel(label) {
                    production().apply { addConfigEntry("session.use_device_allocator_for_initializers", "1") }
                }
            },
            // Three sessions × 2 intra threads spin-wait on 4 big cores while the others load. This
            // is the Q3 lesson (§3.29) applied to the loads themselves rather than to the tokenizer.
            Arm("no_spin") { label ->
                loadParallel(label) { production().apply { addConfigEntry("session.intra_op.allow_spinning", "0") } }
            },
            // Does the thread count set for *inference* also shape the load?
            Arm("intra1") { label ->
                loadParallel(label) { ExecutionPolicy.current.copy(intraThreads = 1, intraOpAffinities = null).warmOptions() }
            },
            // Phase 11A measured serial 12.3 s vs parallel 6.3 s — on the source .onnx with ALL_OPT
            // running every launch. The warm .ort path is a different workload and may not repay the
            // contention, so the comparison is re-run here rather than assumed to still hold.
            Arm("serial") { label -> loadSerial(label) },
        )

        val results = LinkedHashMap<String, MutableList<Long>>()
        repeat(ROUNDS) { round ->
            // Rotate so no arm is always the one that finds the page cache cold.
            val order = arms.drop(round % arms.size) + arms.take(round % arms.size)
            for (arm in order) {
                val ms = arm.run(arm.label)
                results.getOrPut(arm.label) { mutableListOf() } += ms
                Log.i(TAG, "ROUND ${round + 1} ${arm.label} load_ms=$ms")
            }
        }
        results.forEach { (label, samples) ->
            Log.i(TAG, "ARM $label samples=$samples stats=${Stats.of(samples).toJson()}")
        }
    }

    /** Production's warm-path options: NO_OPT over the already-optimized graph, mmapped. */
    private fun production(): SessionOptions = ExecutionPolicy.current.warmOptions()

    private fun OrtTuning.warmOptions(): SessionOptions = toOptions().apply {
        setOptimizationLevel(OptLevel.NO_OPT)
        addConfigEntry("session.load_model_format", "ORT")
        addConfigEntry("session.use_memory_mapped_ort_model", "1")
    }

    /**
     * Loads the three graphs on three threads, as `OnnxModels` does, and returns the wall clock.
     *
     * A `null` from [options] selects the mapped-initializer path, which cannot share the
     * path-based options: ORT ignores `use_memory_mapped_ort_model` for buffer loads and needs
     * `use_ort_model_bytes_directly` instead. The buffers are held until the sessions are closed —
     * ORT reads them for the session's whole life.
     */
    private fun loadParallel(label: String, options: () -> SessionOptions?): Long {
        val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "q18-load").apply { isDaemon = true } }
        val held = java.util.Collections.synchronizedList(ArrayList<ByteBuffer>(3))
        try {
            val start = System.nanoTime()
            val sessions = models.map { file ->
                pool.submit(Callable { open(file, options(), held) })
            }.map { it.get() }
            val ms = (System.nanoTime() - start) / 1_000_000
            sessions.forEach { it.close() }
            held.clear()
            return ms
        } finally {
            pool.shutdown()
            System.gc() // ponytail: crude, but 470 MB per arm × 7 arms needs the buffers actually gone
            Thread.sleep(SETTLE_MS)
        }
    }

    private fun loadSerial(label: String): Long {
        val start = System.nanoTime()
        val sessions = models.map { open(it, production(), null) }
        val ms = (System.nanoTime() - start) / 1_000_000
        sessions.forEach { it.close() }
        System.gc()
        Thread.sleep(SETTLE_MS)
        return ms
    }

    private fun open(file: File, options: SessionOptions?, held: MutableList<ByteBuffer>?): OrtSession {
        if (options != null) return env.createSession(file.absolutePath, options)
        val buffer = RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
        held?.add(buffer)
        val opts = ExecutionPolicy.current.toOptions().apply {
            setOptimizationLevel(OptLevel.NO_OPT)
            addConfigEntry("session.use_ort_model_bytes_directly", "1")
            addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
        }
        return env.createSession(buffer, opts)
    }

    private fun java.io.InputStream.readIntoVoid() {
        val buf = ByteArray(1 shl 20)
        while (read(buf) != -1) { /* touch every byte, keep none */ }
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private class Arm(val label: String, val run: (String) -> Long)

    private companion object {
        const val TAG = "BB.Q18"
        const val ROUNDS = 3
        const val SETTLE_MS = 1_500L
    }
}
