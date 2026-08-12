package com.bhashabridge.app.mt

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.bench.SystemStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Production-path thread + execution-mode A/B — the evidence for queue item Q2b.
 *
 * [MtTuningSweepTest] answers the same question on the *non-production* load path: it leaves
 * `optCache` off so it can vary `optLevel`, so every config there loads the source `.onnx` under
 * ALL_OPT. Production loads a baked `.ort` under NO_OPT with mmap, and `OnnxModels.loadOptions()`
 * builds those options from `tune.toOptions()` — so `intraThreads`, `interThreads`, the execution
 * mode, `cpuArena` and affinity all still apply, but the surrounding graph and allocator behaviour
 * differ. This test therefore keeps `ExecutionPolicy.current` intact (cache on) and varies one knob
 * at a time on top of it.
 *
 * **`SHIPPING` is `ExecutionPolicy.current` unmodified**, and it runs in both suites. Until now no
 * arm here was the shipping configuration — every arm set `intraOpAffinities = null` while production
 * ships intra=2 *with* one pinned worker — so the sweep could not say anything about what users run.
 * Keeping it in both suites also gives the two invocations a common control: if `SHIPPING` disagrees
 * between them, they were taken under different thermal conditions and must not be joined.
 *
 * **Drift handling differs from [MtTuningSweepTest] deliberately.** That test runs each config as one
 * contiguous block, so a config's position in the list contaminates its result — on the SM-S948B the
 * baseline bookends showed +13.4% drift across a 97 s run, roughly +1.2% per position, which is the
 * same order as the effect being measured. Here every config is measured in every round, with the
 * order rotated each round, so position averages out instead of accumulating. Report the median of
 * the pooled samples, and read the `DRIFT` line before believing any between-arm delta.
 *
 * Asserts correctness only — output *and* generated token count identical across arms. Latency is
 * logged, never asserted: a benchmark must not fail on thermal noise.
 *
 * Drive it (two invocations, cool down between; `-e rounds 1 -e runs 2` is a ~70 s smoke run that
 * proves every arm can build a session before committing to the real thing):
 * ```
 * adb shell am instrument -w -e class com.bhashabridge.app.mt.ProductionThreadSweepTest#sweepThreadCounts \
 *   com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class ProductionThreadSweepTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val probe = "The weather is very nice today and I want to go outside."
    private val short = "Water."
    private val warmup = 3
    private val rounds = intArg("rounds", 3)
    private val runsPerRound = intArg("runs", 10)

    /**
     * The thread ladder — what Q2b asks. `intra3` has never been measured on any device, and
     * `intra4` appears twice because §3.21's evidence used the unpinned form: pinned is 4 threads in
     * its *best* shape, so refuting "4 threads wins" needs both.
     */
    @Test
    fun sweepThreadCounts() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        sweep(
            "LADDER",
            listOf(
                "SHIPPING" to base,
                arm(caps, base, "intra1", threads = 1, affinity = false),
                arm(caps, base, "intra2_noAff", threads = 2, affinity = false),
                arm(caps, base, "intra3_aff", threads = 3, affinity = true),
                arm(caps, base, "intra4_aff", threads = 4, affinity = true),
                arm(caps, base, "intra4_noAff", threads = 4, affinity = false),
            ),
        )
    }

    /**
     * Inter-op and the degradation shape.
     *
     * Inter-op threads are **ignored by ORT unless the execution mode is PARALLEL**, so an arm that
     * only sets `interThreads` measures nothing — [arm] forces `parallel = true` alongside it. The
     * `inter1` arm is the control that separates the cost of PARALLEL *mode* from the effect of the
     * inter-op *count*. Phase 7 measured `parallel_inter2` at +10% and reverted it, but that was on
     * the non-production path, and this ledger's own rule is that such results do not transfer.
     *
     * `intra6`/`intra8` are left unpinned on purpose: oversubscribing past the big cluster is only
     * interesting if the extra workers can actually land on the efficiency cores, and unpinned is
     * also the form the SM-S948B run used, so the numbers stay comparable to it.
     */
    @Test
    fun sweepExecModeAndDegradation() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        sweep(
            "EXECMODE",
            listOf(
                "SHIPPING" to base,
                arm(caps, base, "intra2_parallel_inter1", threads = 2, affinity = true, inter = 1),
                arm(caps, base, "intra2_parallel_inter2", threads = 2, affinity = true, inter = 2),
                arm(caps, base, "intra6_noAff", threads = 6, affinity = false),
                arm(caps, base, "intra8_noAff", threads = 8, affinity = false),
            ),
        )
    }

    /**
     * One intra-op thread against two, counterbalanced — the arm §3.38 could not settle.
     *
     * On the SM-S948B `intra1` came out −6.3% against `SHIPPING` but only −3.3% against `SHIPPING`'s
     * own duplicate, with a worse stdev, i.e. at the repeatability floor. Six arms of ladder are the
     * wrong instrument for a two-way question: each arm gets a third of the run and the floor has to
     * be inferred from whichever duplicate pair happens to exist.
     *
     * Here both configurations are run **twice**, so `_a` against `_b` measures this run's own floor
     * while `intra1` against `intra2` measures the effect. If the two `intra2` arms disagree by as
     * much as `intra1` differs from them, there is no result — and that is a verdict, not a failure.
     */
    @Test
    fun sweepOneVsTwo() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        sweep(
            "ONEVSTWO",
            listOf(
                arm(caps, base, "intra1_a", threads = 1, affinity = false),
                arm(caps, base, "intra2_a", threads = 2, affinity = true),
                arm(caps, base, "intra1_b", threads = 1, affinity = false),
                arm(caps, base, "intra2_b", threads = 2, affinity = true),
            ),
        )
    }

    /**
     * What KleidiAI's kernels are worth on silicon that has something for them to dispatch to.
     *
     * `mlas.disable_kleidiai` forces MLAS's own kernels, so the delta is what KleidiAI (and, on an
     * SME part, its `smopa` kernels) contributes. Measured at **two thread counts** so the ISA effect
     * and the thread effect cannot confound each other — the arms are otherwise identical to their
     * KleidiAI-on partners.
     *
     * Pointless on a part with no dotprod/i8mm/SME (the SM-M315F): there is nothing to dispatch to
     * and the A/B measures noise at the cost of a thermally loaded run. Skip it there.
     */
    @Test
    fun sweepKleidiAi() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        val shippingThreads = base.intraThreads ?: 1
        sweep(
            "KLEIDI",
            listOf(
                "SHIPPING" to base,
                "SHIPPING_noKleidiAI" to base.copy(name = "SHIPPING_noKleidiAI", disableKleidiAi = true),
                arm(caps, base, "intra4", threads = 4, affinity = false),
                arm(caps, base, "intra4_noKleidiAI", threads = 4, affinity = false, noKleidiAi = true),
                // Counterbalance pair at the shipping thread count, where the effect is largest.
                // Byte-identical to the first two arms, so their disagreement IS this run's
                // repeatability floor — read it before reading the KleidiAI delta.
                "SHIPPING_recheck" to base.copy(name = "SHIPPING_recheck"),
                "SHIPPING_noKleidiAI_recheck" to base.copy(name = "SHIPPING_noKleidiAI_recheck", disableKleidiAi = true),
            ),
        )
        Log.i(TAG, "KLEIDI_CONTEXT shippingThreads=$shippingThreads i8mm=${caps.i8mm} sme=${caps.sme} sme2=${caps.sme2} dotprod=${caps.dotProduct}")
    }

    /**
     * Is KleidiAI's regression a property of the kernels, or of what the load path hands them?
     *
     * §3.20 measured KleidiAI **on** as faster on this device on 2026-07-31; §3.39 measured it
     * **slower**, three times, on the same device. Between those sits §3.30, which changed the
     * weights themselves into one shared blob per direction, and the `.ort` bake that re-inlines it.
     * Model assets are gitignored, so a source-only bisect cannot revert that half — but this can
     * separate it without any rebuild: run KleidiAI on/off under **both** load paths at one fixed
     * thread count.
     *
     * - `optCache = true` — production: baked `.ort`, NO_OPT, mmap, shared blob re-inlined.
     * - `optCache = false` — the source `.onnx` under ALL_OPT, i.e. the graph as exported.
     *
     * Absolute latencies are not comparable between the two cache settings and are not meant to be.
     * The reading is the **KleidiAI delta within each**: if it is negative under both, the kernels
     * are simply slower here and §3.20 was a bad measurement. If it flips sign with the load path,
     * the artifact is implicated and the fix is upstream of `disableKleidiAi`.
     */
    @Test
    fun sweepKleidiAiVsCache() {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        // Entry #9's A/B ran at 4 threads, so 4 is the default for comparability — but that is the
        // arm where the effect is smallest (§3.39: 3-6% at intra4 against 10-13% at intra2), so
        // `-e threads 2` puts the question where the signal actually is.
        val threads = intArg("threads", FIXED_THREADS)
        fun a(label: String, cache: Boolean, noKleidi: Boolean) =
            label to base.copy(
                name = label,
                intraThreads = threads,
                intraOpAffinities = null,
                optCache = cache,
                disableKleidiAi = noKleidi,
            )
        sweep(
            "KLEIDIxCACHE",
            listOf(
                a("cacheON_kleidiON", cache = true, noKleidi = false),
                a("cacheON_kleidiOFF", cache = true, noKleidi = true),
                a("cacheOFF_kleidiON", cache = false, noKleidi = false),
                a("cacheOFF_kleidiOFF", cache = false, noKleidi = true),
                // Repeat the production pair last: within-run control for the whole comparison.
                a("cacheON_kleidiON_recheck", cache = true, noKleidi = false),
                a("cacheON_kleidiOFF_recheck", cache = true, noKleidi = true),
            ),
        )
        Log.i(TAG, "KLEIDIxCACHE_CONTEXT threads=$threads caps=${caps.describe()}")
    }

    // ---------------------------------------------------------------------------------------------

    /**
     * Builds one arm off [base], changing only what the arm is about.
     *
     * The affinity string is **always** regenerated by [ExecutionPolicy.affinityString] and never
     * inherited: `base` carries exactly one `;`-group (production runs 2 threads), and ORT rejects a
     * session whose group count is not `intraThreads - 1`, so inheriting it would throw at
     * `createSession` for every arm with a different thread count. The [require] turns that from a
     * failure ~2.4 s into a session load into a failure in microseconds.
     *
     * A device with no distinct big/LITTLE split cannot pin anything; rather than silently producing
     * a second copy of the unpinned arm, the label says so.
     */
    private fun arm(
        caps: CpuCapabilities,
        base: OrtTuning,
        label: String,
        threads: Int,
        affinity: Boolean,
        inter: Int? = null,
        noKleidiAi: Boolean = false,
    ): Pair<String, OrtTuning> {
        val aff = if (affinity) ExecutionPolicy.affinityString(caps, threads) else null
        val name = if (affinity && aff == null) "${label}_noPinAvailable" else label
        require(aff == null || aff.split(";").size == threads - 1) {
            "$name: ORT needs ${threads - 1} affinity groups, got '$aff'"
        }
        return name to base.copy(
            name = name,
            intraThreads = threads,
            intraOpAffinities = aff,
            interThreads = inter,
            // Inter-op threads do nothing in SEQUENTIAL mode; without this the arm is a no-op.
            parallel = inter != null,
            disableKleidiAi = noKleidiAi,
        )
    }

    private fun sweep(suite: String, configs: List<Pair<String, OrtTuning>>) {
        val caps = CpuCapabilities.detect()
        val base = ExecutionPolicy.current
        val entry = SystemStats.capture(context, "$suite-entry")

        Log.i(TAG, "SUITE $suite rounds=$rounds runs=$runsPerRound arms=${configs.size}")
        Log.i(TAG, "TOPOLOGY ${caps.describe()}")
        Log.i(
            TAG,
            "PRODUCTION base=${base.name} intra=${base.intraThreads} arena=${base.cpuArena} " +
                "optCache=${base.optCache} affinity=${base.intraOpAffinities ?: "OFF"}",
        )
        Log.i(
            TAG,
            "BATTERY_START tempC=${entry.batteryTempC} status=${entry.batteryStatus} " +
                "plugged=${entry.batteryPlugged} level=${entry.batteryLevelPct}",
        )
        // Warn, never fail: a benchmark that fails on temperature is a benchmark nobody runs. The
        // device is on USB and therefore charging, which is itself a known +temperature confound —
        // these numbers are only comparable against another charging run.
        val startTemp = entry.batteryTempC
        if (startTemp != null && startTemp > WARM_START_C) {
            Log.w(TAG, "ENTRY_TEMP ${startTemp}C > ${WARM_START_C}C — not a cold device; every between-arm delta carries that caveat")
        }
        if (entry.unavailable.isNotEmpty()) Log.i(TAG, "UNAVAILABLE ${entry.unavailable}")

        // Bake the .ort once, untimed. The cache stamp carries no tuning field, so exactly one bake
        // happens for the whole sweep — and without this line it happens inside round 0's first
        // measured arm, charging one arbitrary arm several seconds of graph optimization.
        MtEngine(context, DIRECTION, GreedyDecoder(), base).release()

        val counter = CountingDecoder(GreedyDecoder())
        val acc = configs.associate { (label, _) -> label to Acc() }
        var refLong: String? = null
        var refShort: String? = null
        var refTokLong = -1
        var refTokShort = -1

        for (round in 0 until rounds) {
            // Rotate so no config keeps the coolest (or hottest) slot across rounds.
            val order = configs.drop(round % configs.size) + configs.take(round % configs.size)
            for ((label, tune) in order) {
                val a = acc.getValue(label)
                val engine = MtEngine(context, DIRECTION, counter, tune)
                try {
                    repeat(warmup) { engine.translate(probe) }

                    // Snapshots bracket the timed runs only — construction, warm-up and the parity
                    // translations are outside, so the CPU-time and frequency deltas describe the
                    // measured work and nothing else.
                    val pre = SystemStats.capture(context, "$label-r$round-pre")
                    val schedPre = schedTotals()
                    val roundLong = ArrayList<Long>(runsPerRound)
                    repeat(runsPerRound) {
                        roundLong += timeMs { engine.translate(probe) }
                        a.short += timeMs { engine.translate(short) }
                    }
                    val schedPost = schedTotals()
                    val post = SystemStats.capture(context, "$label-r$round-post")
                    a.long += roundLong
                    a.record(pre, post, caps, Stats.of(roundLong).median, schedPost - schedPre)

                    val outLong = engine.translate(probe)
                    val tokLong = counter.lastGenerated
                    val outShort = engine.translate(short)
                    val tokShort = counter.lastGenerated
                    if (refLong == null) {
                        refLong = outLong; refShort = outShort
                        refTokLong = tokLong; refTokShort = tokShort
                        Log.i(TAG, "REFERENCE $suite longTokens=$refTokLong shortTokens=$refTokShort out=\"$outLong\"")
                    }
                    assertEquals("arm $label changed long output", refLong, outLong)
                    assertEquals("arm $label changed short output", refShort, outShort)
                    // The string check alone cannot see two different id sequences that detokenize to
                    // the same text; tokens/sec is derived from this count, so it has to be pinned.
                    assertEquals("arm $label changed long token count", refTokLong, tokLong)
                    assertEquals("arm $label changed short token count", refTokShort, tokShort)

                    Log.i(
                        TAG,
                        "ROUND $suite $label r${round + 1}/$rounds longMedian=${f(Stats.of(roundLong).median)}ms " +
                            "tempC=${post.batteryTempC}",
                    )
                } finally {
                    engine.release()
                }
            }
        }

        val exit = SystemStats.capture(context, "$suite-exit")
        Log.i(TAG, "BATTERY_END tempC=${exit.batteryTempC} level=${exit.batteryLevelPct}")

        for ((label, tune) in configs) {
            val a = acc.getValue(label)
            val long = Stats.of(a.long)
            val shortStats = Stats.of(a.short)
            val translations = a.long.size + a.short.size
            val cpuSeconds = a.cpuSeconds()
            val wallSeconds = a.wallMs / 1000.0
            val coresBusy = if (wallSeconds > 0) cpuSeconds / wallSeconds else 0.0
            val cpuMsPerTx = if (translations > 0) cpuSeconds * 1000.0 / translations else 0.0

            Log.i(TAG, "RESULT $suite LONG $label ${long.toJson()} tokens=$refTokLong tokPerSec=${f(rate(refTokLong, long.median))}")
            Log.i(TAG, "RESULT $suite SHORT $label ${shortStats.toJson()} tokens=$refTokShort tokPerSec=${f(rate(refTokShort, shortStats.median))}")
            Log.i(
                TAG,
                "SYS $suite $label intra=${tune.intraThreads} inter=${tune.interThreads} " +
                    "parallel=${tune.parallel} affinity=${tune.intraOpAffinities ?: "OFF"} " +
                    "coresBusy=${f(coresBusy)} cpuMsPerTx=${f(cpuMsPerTx)} migrations=${a.migrations} " +
                    "nonvolCtxt=${a.nonvolCtxt} perfFreqMHz=${a.perfFreqMhz} " +
                    "tempC=${a.tempFirst}->${a.tempLast} pssKb=${a.pssFirst}->${a.pssLast} " +
                    "roundMedians=${a.roundMedians.map { f(it) }}",
            )
        }

        // Pooled drift across arms: rotation removes the systematic position effect, this says how
        // much the whole run heated up underneath it. Above ~5% the run is thermally dominated.
        val firstRound = configs.mapNotNull { acc.getValue(it.first).roundMedians.firstOrNull() }
        val lastRound = configs.mapNotNull { acc.getValue(it.first).roundMedians.lastOrNull() }
        if (firstRound.isNotEmpty() && lastRound.isNotEmpty()) {
            val r1 = firstRound.average()
            val rN = lastRound.average()
            Log.i(TAG, "DRIFT $suite firstRound=${f(r1)}ms lastRound=${f(rN)}ms ratio=${f(rN / r1)}")
        }

        assertTrue("no samples collected", acc.values.all { it.long.isNotEmpty() })
    }

    /**
     * Per-arm accumulator. Everything here is a delta between two [SystemStats] snapshots, i.e. an
     * exact kernel counter rather than a sampled estimate — which is why this test needs no
     * background sampler thread.
     */
    private class Acc {
        val long = ArrayList<Long>()
        val short = ArrayList<Long>()
        val roundMedians = ArrayList<Double>()
        val perfFreqMhz = ArrayList<Long>()
        var cpuTicks = 0L
        var clockTicksPerSec = 0L
        var wallMs = 0L
        var migrations = 0L
        var nonvolCtxt = 0L
        var tempFirst: Double? = null
        var tempLast: Double? = null
        var pssFirst: Long? = null
        var pssLast: Long? = null

        fun record(
            pre: SystemStats,
            post: SystemStats,
            caps: CpuCapabilities,
            roundMedian: Double,
            sched: Sched,
        ) {
            roundMedians += roundMedian
            wallMs += post.elapsedRealtimeMs - pre.elapsedRealtimeMs

            val t0 = pre.processCpuTicks
            val t1 = post.processCpuTicks
            if (t0 != null && t1 != null) cpuTicks += t1 - t0
            pre.clockTicksPerSec?.let { clockTicksPerSec = it }

            migrations += sched.migrations
            nonvolCtxt += sched.involuntarySwitches

            // `post` is read microseconds after the last translation, so this is an in-load
            // frequency; `pre` is an idle read between arms and is not worth keeping.
            post.perCoreFreqKhz?.let { freqs ->
                val ids = caps.performanceCoreIds.ifEmpty { freqs.indices.toList() }
                val readable = ids.mapNotNull { freqs.getOrNull(it) }.filter { it > 0L }
                if (readable.isNotEmpty()) perfFreqMhz += (readable.average() / 1000.0).toLong()
            }

            if (tempFirst == null) tempFirst = pre.batteryTempC
            post.batteryTempC?.let { tempLast = it }
            if (pssFirst == null) pssFirst = pre.totalPssKb
            // A monotone rise across the arms means something is leaking and the late arms are not
            // comparable to the early ones.
            pre.totalPssKb?.let { pssLast = it }
        }

        /**
         * CPU seconds burned by the **process** over the measured windows. Whole-process, so it
         * includes the JUnit runner, GC and Binder threads — an additive constant that is the same
         * for every arm, not zero. USER_HZ is typically 100, so a ~10 ms quantum makes this useless
         * per translation and fine over an arm's ~10 s of measured work.
         *
         * There is deliberately no system-wide utilization figure: `/proc/stat` has been SELinux-
         * blocked for apps since Android 8 and *reads back empty rather than failing*, so any
         * system-wide number would be a plausible-looking zero.
         */
        fun cpuSeconds(): Double =
            if (clockTicksPerSec > 0L) cpuTicks.toDouble() / clockTicksPerSec else 0.0
    }

    /** Scheduler counters, summed over every thread in the process. */
    private data class Sched(val migrations: Long, val involuntarySwitches: Long) {
        operator fun minus(other: Sched) =
            Sched(migrations - other.migrations, involuntarySwitches - other.involuntarySwitches)
    }

    /**
     * Sums `nr_migrations` and `nr_involuntary_switches` across `/proc/self/task/&#42;/sched`.
     *
     * `SystemStats` reads `/proc/self/sched` and `/proc/self/status`, and both of those describe the
     * **thread-group leader** — the app's idle main thread, which neither translates nor belongs to
     * ORT's intra-op pool. That reported exactly 0 for every arm: a plausible-looking zero, which is
     * worse than no number. Affinity's entire claim is that pinning suppresses migrations, so the
     * counter has to cover the threads doing the work.
     *
     * Read outside the timed blocks. A delta can in principle go negative if a thread exits inside
     * the window; the engine is fully constructed before the window opens, so its pool is stable.
     */
    private fun schedTotals(): Sched {
        var migrations = 0L
        var involuntary = 0L
        java.io.File("/proc/self/task").listFiles()?.forEach { task ->
            runCatching {
                java.io.File(task, "sched").forEachLine { line ->
                    // Match the key exactly: the file also carries se.statistics.nr_migrations_cold,
                    // which a substring match would fold in.
                    when (line.substringBefore(':').trim()) {
                        "se.nr_migrations" -> migrations += schedValue(line)
                        "nr_involuntary_switches" -> involuntary += schedValue(line)
                    }
                }
            }
        }
        return Sched(migrations, involuntary)
    }

    /** `se.nr_migrations                             :                    12` */
    private fun schedValue(line: String): Long =
        line.substringAfter(':').trim().substringBefore('.').toLongOrNull() ?: 0L

    private inline fun timeMs(block: () -> Unit): Long {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
    }

    private fun rate(tokens: Int, medianMs: Double): Double =
        if (medianMs > 0.0) tokens / (medianMs / 1000.0) else 0.0

    private fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v)

    private fun intArg(key: String, default: Int): Int =
        InstrumentationRegistry.getArguments().getString(key)?.toIntOrNull() ?: default

    private companion object {
        const val TAG = "BB_PROD_SWEEP"
        val DIRECTION = Direction.EN_TO_HI
        /** Entry #9's KleidiAI A/B ran at 4 threads; matching it keeps the two sessions comparable. */
        const val FIXED_THREADS = 4
        /** Above this at entry the device is already warm and the run is not a cold-start comparison. */
        const val WARM_START_C = 35.0
    }
}
