package com.bhashabridge.app.smoke

import com.bhashabridge.app.bench.Stats
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Purpose:  The portable half of the smoke test — CPU work that needs no model, no assets and no
 *           permissions, so the app produces a result on a phone it has never seen.
 * Owns:     A short-lived thread pool per measurement.
 * Lifetime: One run.
 * Thread:   [run] blocks its caller and fans out internally.
 *
 * **What this does and does not prove.** These kernels are ordinary Kotlin. They stress the integer
 * and floating-point pipelines and will be auto-vectorised by ART to whatever the core supports,
 * which makes them a fair *relative* score across devices and a genuine thermal and power load. They
 * are **not** proof that i8mm or SME executed — nothing written in Kotlin can be, because the
 * instruction selection is ART's, not ours. That claim needs the real INT8 graphs and the KleidiAI
 * A/B, which is why [MtWorkload] carries it and this file does not pretend to.
 *
 * The int8 dot product is the shape that matters: it is the inner loop of a quantised transformer,
 * accumulating `int8 × int8 → int32`. If a device is unexpectedly slow on the real model, the ratio
 * between its score here and elsewhere says whether the cause is the CPU or the runtime.
 */
object SyntheticWorkload {

    /** One kernel's result. [opsPerSec] is the comparable number; the rest explains it. */
    class KernelResult(
        val name: String,
        val threads: Int,
        val opsPerSec: Double,
        val perIterationMs: Stats,
        val checksum: Long,
    )

    private const val VECTOR = 4096
    private const val MATRIX = 192          // 192³ ≈ 7.1 M MACs per iteration — ~10-40 ms on a phone core
    private const val MIN_ITERATIONS = 12
    private const val WARMUP = 4

    /**
     * Default minimum wall time per (kernel, thread-count) pair — `Preset.STANDARD`'s budget.
     *
     * A fixed iteration count is the wrong budget here: the first real run finished all three
     * kernels in 2.1 s, which produced five sampler points, a 0.0 °C thermal rise and no usable
     * throttling signal — it measured a phone that never got warm. Six pairs × 4 s is ~25 s of
     * continuous load, which is enough for the governor to settle and for the battery temperature to
     * actually move. Fast devices simply do more iterations in the same time, which is also what
     * makes the ops/sec comparable across devices rather than the wall time.
     *
     * Longer budgets are a *thermal* instrument, not a precision one: ops/sec is a rate, so it stays
     * comparable across presets, but the value under a 15 s budget is measured on a hot core and the
     * 1.5 s one on a cold core, which is exactly the difference `Preset.TORTURE` exists to expose.
     */
    const val DEFAULT_MIN_DURATION_MS = 4_000L

    /**
     * Runs every kernel at 1 thread and at [maxThreads], so the report carries both single-core
     * capability and how well the part actually scales. Scaling is the interesting number on a
     * big.LITTLE device: a 4× thread increase that buys 1.6× is a scheduler and memory-bandwidth
     * story, and it is exactly the story the MT engine's `intraThreads = 2` policy came out of.
     *
     * [minDurationMs] is the floor per (kernel, thread-count) pair; [MIN_ITERATIONS] still applies,
     * so a very slow device is never cut off mid-sample.
     */
    fun run(
        maxThreads: Int,
        minDurationMs: Long = DEFAULT_MIN_DURATION_MS,
        onProgress: (String) -> Unit,
    ): List<KernelResult> {
        val results = ArrayList<KernelResult>(6)
        val threadCounts = if (maxThreads > 1) listOf(1, maxThreads) else listOf(1)

        for (t in threadCounts) {
            onProgress("int8 dot product · ${t}t")
            results += measure("int8_dot", t, minDurationMs) { int8Dot() }

            onProgress("fp32 GEMM · ${t}t")
            results += measure("fp32_gemm", t, minDurationMs) { fp32Gemm() }

            onProgress("int32 MAC chain · ${t}t")
            results += measure("int32_mac", t, minDurationMs) { int32Mac() }
        }
        return results
    }

    /**
     * Times [kernel] on [threads] threads. Every thread runs the same kernel on its own data, so
     * there is no sharing, no false sharing, and the only contention is the memory system and the
     * scheduler — which is what a thread-scaling number should be measuring.
     *
     * The reported latency is the *slowest* thread in each round, not the mean: a round is only
     * finished when its last thread is, and reporting the mean would hide a core that got parked on
     * the little cluster.
     */
    private fun measure(
        name: String,
        threads: Int,
        minDurationMs: Long,
        kernel: () -> Pair<Long, Long>,
    ): KernelResult {
        val pool = Executors.newFixedThreadPool(threads) { r -> Thread(r, "bench-synth").apply { isDaemon = true } }
        try {
            repeat(WARMUP) { pool.invokeAll((0 until threads).map { java.util.concurrent.Callable { kernel() } }) }

            val perRound = ArrayList<Long>(64)
            var checksum = 0L
            var totalOps = 0L
            val start = System.nanoTime()
            val deadline = start + minDurationMs * 1_000_000
            var round = 0
            // Run to the later of the iteration floor and the time budget, so a slow device is not
            // held to a stopwatch and a fast one still produces a long enough thermal load.
            while (round < MIN_ITERATIONS || System.nanoTime() < deadline) {
                val t0 = System.nanoTime()
                val futures = pool.invokeAll((0 until threads).map { java.util.concurrent.Callable { kernel() } })
                // Slowest thread defines the round.
                perRound += (System.nanoTime() - t0) / 1_000_000
                futures.forEach { f ->
                    val (ops, sum) = f.get()
                    totalOps += ops
                    checksum = checksum xor sum
                }
                round++
            }
            val elapsedSec = (System.nanoTime() - start) / 1e9
            return KernelResult(
                name = name,
                threads = threads,
                opsPerSec = if (elapsedSec > 0) totalOps / elapsedSec else 0.0,
                perIterationMs = Stats.of(perRound),
                checksum = checksum,
            )
        } finally {
            pool.shutdown()
            pool.awaitTermination(5, TimeUnit.SECONDS)
        }
    }

    // ---- kernels. Each returns (operations performed, checksum). ----------------------------------
    //
    // The checksum exists to stop the JIT deleting the loop. It is returned, XOR-accumulated and
    // written into the report: a value that reaches output cannot be dead code, which is the only
    // reliable way to keep a microbenchmark honest without a framework.

    /** int8 × int8 → int32 accumulate: the quantised-transformer inner loop. */
    private fun int8Dot(): Pair<Long, Long> {
        val a = ByteArray(VECTOR) { (it % 127).toByte() }
        val b = ByteArray(VECTOR) { ((it * 7) % 127).toByte() }
        var acc = 0
        var reps = 0
        while (reps < 64) {
            var i = 0
            while (i < VECTOR) {
                acc += a[i] * b[i]
                i++
            }
            reps++
        }
        return (VECTOR.toLong() * 64) to acc.toLong()
    }

    /** Dense fp32 matrix multiply — the classic cache-and-FPU probe. */
    private fun fp32Gemm(): Pair<Long, Long> {
        val n = MATRIX
        val a = FloatArray(n * n) { (it % 17).toFloat() }
        val b = FloatArray(n * n) { (it % 13).toFloat() }
        val c = FloatArray(n * n)
        for (i in 0 until n) {
            for (k in 0 until n) {
                val aik = a[i * n + k]
                if (aik == 0f) continue
                var j = 0
                val rowB = k * n
                val rowC = i * n
                while (j < n) {
                    c[rowC + j] += aik * b[rowB + j]
                    j++
                }
            }
        }
        return (n.toLong() * n * n) to c[0].toLong() + c[n * n - 1].toLong()
    }

    /** Long dependent-integer chain: latency-bound, so it exposes clock rather than width. */
    private fun int32Mac(): Pair<Long, Long> {
        var x = 1
        var i = 0
        val n = 2_000_000
        while (i < n) {
            x = x * 31 + i
            i++
        }
        return n.toLong() to x.toLong()
    }
}
