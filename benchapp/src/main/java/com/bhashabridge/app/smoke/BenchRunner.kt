package com.bhashabridge.app.smoke

import android.content.Context
import android.os.Build
import com.bhashabridge.app.Direction
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.bench.SystemStats
import com.bhashabridge.app.logError
import com.bhashabridge.app.mt.CpuCapabilities
import com.bhashabridge.app.mt.ExecutionPolicy
import java.io.File

/**
 * Purpose:  Runs the smoke test end to end and produces one JSON report.
 * Owns:     Nothing beyond a run; the sampler and workloads own their own resources.
 * Lifetime: One run.
 * Thread:   [run] blocks. Call it off the main thread.
 *
 * Order is deliberate. The device is characterised first (free, and valid even if everything after
 * it fails), then the portable CPU kernels, then the model phase if graphs are present. Sampling
 * wraps the *workload* phases only — including the idle stretches would drag every average toward
 * an idle device and hide the thermal ramp the sampler exists to catch.
 */
class BenchRunner(private val context: Context) {

    /** What a run produced, plus whether the MT half actually ran. */
    class Report(
        val json: String,
        val file: File?,
        /** The one or two numbers worth reading from across the room. Null when nothing ran. */
        val headline: Headline?,
        val sections: List<Section>,
        /** Per-core share of this process's work, for the bar chart. Null when unsampled. */
        val coreShare: CoreShare?,
        val mtRan: Boolean,
    )

    /**
     * The result, as a number and a unit.
     *
     * A benchmark whose answer is buried in row fourteen of a monospace block has not finished
     * reporting. This is what the screen leads with; everything else is support.
     */
    class Headline(val value: String, val unit: String, val caption: String)

    class Section(val title: String, val rows: List<Row>)

    /** [tone] drives colour only — the value is the same string either way. */
    class Row(val label: String, val value: String, val tone: Tone = Tone.NORMAL)

    enum class Tone { NORMAL, GOOD, WARN, MUTED }

    /** Shares sum to ~1 across cores; [performanceCoreIds] marks which bars are big cores. */
    class CoreShare(val shares: List<Double>, val performanceCoreIds: List<Int>)

    /**
     * How hard to push, as one dial.
     *
     * Every budget in a run — synthetic wall time, MT iteration count, sustained load — moves
     * together, because moving one alone produces a report that is neither quick nor conclusive: a
     * 60 s sustained phase after an 8-iteration MT phase spends its time proving something the
     * latency sample is too small to attribute. Four steps rather than a slider so that reports are
     * comparable: two devices are only comparable **within the same preset**, and a named preset
     * travels in the JSON where a free-form seconds value would not be noticed.
     *
     * [STANDARD] is deliberately the previous fixed configuration, so every number already recorded
     * in `README.md` and `bench/results/` stays valid without a re-run.
     */
    enum class Preset(
        val label: String,
        /** Minimum wall time per (kernel, thread-count) pair in the synthetic phase. */
        val syntheticMinMs: Long,
        val mtIterations: Int,
        val sustainedSeconds: Int,
        /** Rough full-run wall time with models staged, for the button caption. Not a measurement. */
        val approxMinutes: Int,
    ) {
        /** Triage: is this device broken or merely slow. No thermal claim — nothing runs long enough. */
        LIGHT("Light", syntheticMinMs = 1_500, mtIterations = 8, sustainedSeconds = 0, approxMinutes = 1),

        /** The default, and the configuration every published baseline was measured with. */
        STANDARD("Standard", syntheticMinMs = 4_000, mtIterations = 20, sustainedSeconds = 60, approxMinutes = 3),

        /** Long enough for the governor to give up the boost clock and for throttling to show. */
        HEAVY("Heavy", syntheticMinMs = 8_000, mtIterations = 40, sustainedSeconds = 180, approxMinutes = 6),

        /** Thermal soak: what the device still delivers with its budget fully spent. */
        TORTURE("Torture", syntheticMinMs = 15_000, mtIterations = 60, sustainedSeconds = 600, approxMinutes = 14),

        ;

        /**
         * Fewer than [mtIterations], and deliberately so: recognition of the fixture takes 1.5–2 s
         * against ~0.5 s for a translation, and its run-to-run spread is far tighter (stdev under
         * 2% on the M31, against 5–10% for MT). Matching the MT count would triple this phase's
         * wall time to sharpen a median that is already sharp.
         */
        val speechIterations: Int get() = maxOf(3, mtIterations / 4)
    }

    class Config(
        val direction: Direction = Direction.EN_TO_HI,
        val preset: Preset = Preset.STANDARD,
        val includeKleidiAb: Boolean = true,
        val includeSynthetic: Boolean = true,
        val includeSpeech: Boolean = true,
    )

    /** Set when the MT phase threw, so the digest can say why rather than just "skipped". */
    private var mtError: String? = null

    /** Same, for speech. Kept separate so one phase failing never explains the other's absence. */
    private var speechError: String? = null

    fun run(config: Config, onProgress: (String) -> Unit): Report {
        mtError = null
        speechError = null
        val caps = CpuCapabilities.detect()
        val policy = ExecutionPolicy.current
        val before = SystemStats.capture(context, "before")

        val sampler = CoreSampler(context)
        sampler.start()

        val mtReady = ModelStore.isReady(context, config.direction)

        val synthetic = if (config.includeSynthetic) {
            onProgress("synthetic CPU kernels")
            // Without models the synthetic phase is the *only* load, so a heavy preset would
            // otherwise heat the phone for 90 s and call it a soak. The sustained budget it cannot
            // spend on translation moves here instead, split across the six kernel/thread pairs.
            val perPair =
                if (mtReady) config.preset.syntheticMinMs
                else maxOf(config.preset.syntheticMinMs, config.preset.sustainedSeconds * 1000L / 6)
            // The policy's own thread count is the interesting comparison point, but a 1-vs-N
            // scaling curve needs N to be the whole performance cluster, not the 2 threads the MT
            // policy settled on.
            SyntheticWorkload.run(maxOf(1, caps.performanceCores), perPair) { onProgress(it) }
        } else emptyList()

        val mt = if (mtReady) {
            runCatching {
                MtWorkload(context, config.direction).run(
                    iterations = config.preset.mtIterations,
                    sustainedSeconds = config.preset.sustainedSeconds,
                    includeKleidiAb = config.includeKleidiAb,
                    onProgress = onProgress,
                )
            }.getOrElse { e ->
                // Logged, not just flashed on the status line: the progress text is overwritten
                // within a second by the next phase, and the first on-device failure here was a
                // missing dictionary that the UI reported only as "no models staged".
                logError(LogTag.BENCH, "MT phase failed", e)
                mtError = "${e::class.java.simpleName}: ${e.message}"
                onProgress("MT phase failed: ${e.message}")
                null
            }
        } else null

        // After MT, and inside the sampled window on purpose: recognition is a different load shape
        // from a transformer — one long single-threaded FST search rather than batched GEMMs — and
        // the per-core chart is more informative for having both in it.
        val speech = if (config.includeSpeech && SpeechWorkload.isReady(context, config.direction)) {
            runCatching {
                SpeechWorkload(context, config.direction).run(
                    iterations = config.preset.speechIterations,
                    // The leak churn is a fixed cost that says nothing about speed, so it is off on
                    // the triage preset where the whole point is to finish in a minute.
                    checkLeak = config.preset != Preset.LIGHT,
                    onProgress = onProgress,
                )
            }.getOrElse { e ->
                logError(LogTag.BENCH, "Speech phase failed", e)
                speechError = "${e::class.java.simpleName}: ${e.message}"
                onProgress("speech phase failed: ${e.message}")
                null
            }
        } else null

        val samples = sampler.stop()
        val after = SystemStats.capture(context, "after")

        onProgress("writing report")
        val json = BenchReport.build(
            caps = caps,
            policy = policy,
            preset = config.preset,
            synthetic = synthetic,
            mt = mt,
            speech = speech,
            samplerSummary = CoreSampler.summarise(samples),
            samples = samples,
            before = before,
            after = after,
            missingModels = if (mtReady) emptyList() else ModelStore.missing(context, config.direction),
        )
        val file = BenchReport.write(context, json)

        val summary = CoreSampler.summarise(samples)
        return Report(
            json = json,
            file = file,
            headline = headline(synthetic, mt),
            sections = sections(caps, config.preset, synthetic, mt, speech, summary, before, after),
            coreShare = (summary["coreShare"] as? List<*>)
                ?.map { it as Double }
                ?.takeIf { it.isNotEmpty() }
                ?.let { CoreShare(it, caps.performanceCoreIds) },
            mtRan = mt != null,
        )
    }

    /**
     * The lead number: translation latency when the model ran, otherwise the int8 kernel score.
     *
     * Deliberately one figure. Two "headlines" is no headline, and translation latency is the thing
     * every other number in the report exists to explain.
     */
    private fun headline(
        synthetic: List<SyntheticWorkload.KernelResult>,
        mt: MtWorkload.Result?,
    ): Headline? {
        if (mt != null) {
            return Headline(
                value = round1(mt.endToEnd.median).toString(),
                unit = "ms",
                caption = "per translation · ${mt.tokensIn} → ${mt.tokensOut} tokens · ${round1(mt.tokensPerSec)} tok/s",
            )
        }
        val int8 = synthetic.filter { it.name == "int8_dot" }.maxByOrNull { it.threads } ?: return null
        return Headline(
            value = fmtOpsValue(int8.opsPerSec),
            unit = fmtOpsUnit(int8.opsPerSec),
            caption = "int8 dot product · ${int8.threads} threads · CPU phase only",
        )
    }

    /**
     * The on-screen digest, grouped.
     *
     * Grouping is the whole readability fix. Twenty rows of equal weight in one block is a wall
     * nobody scans; the same rows under five headings are five short lists, and a reader looking for
     * "why is decode slow" goes straight to Inference. Labels are also short enough to sit in a
     * narrow column without the value wrapping, which is what made the first version unreadable.
     */
    private fun sections(
        caps: CpuCapabilities,
        preset: Preset,
        synthetic: List<SyntheticWorkload.KernelResult>,
        mt: MtWorkload.Result?,
        speech: SpeechWorkload.Result?,
        s: Map<String, Any?>,
        before: SystemStats,
        after: SystemStats,
    ): List<Section> {
        val out = ArrayList<Section>(7)

        // ---- Inference. First, because it is the reason the app exists. ----
        if (mt != null) {
            val rows = ArrayList<Row>(8)
            rows += Row("Tokens", "${mt.tokensIn} in → ${mt.tokensOut} out")
            rows += Row("Translate", "${round1(mt.endToEnd.median)} ms", Tone.GOOD)
            rows += Row("p95", "${round1(mt.endToEnd.p95)} ms", Tone.MUTED)
            rows += Row("Throughput", "${round1(mt.tokensPerSec)} tok/s")
            rows += Row("Encoder", "${round1(mt.encoderMs.median)} ms")
            rows += Row("decoder_init", "${round1(mt.decoderInitMs.median)} ms")
            rows += Row("decoder_step", "${round1(mt.decoderStepMs.median)} ms/tok")
            rows += Row("Session load", "${mt.sessionLoadMs} ms", Tone.MUTED)
            mt.kleidiAi?.let {
                // ≈1.0 means no microkernel dispatched on this core — a real answer, so it is not
                // flagged as a problem, only as unremarkable.
                val tone = if (it.speedup >= 1.05) Tone.GOOD else Tone.MUTED
                rows += Row("KleidiAI", "${round2(it.speedup)}×", tone)
            }
            out += Section("Inference", rows)
        } else {
            out += Section(
                "Inference",
                listOf(
                    Row(
                        "Status",
                        mtError?.let { "failed — $it" } ?: "skipped — no models staged",
                        if (mtError != null) Tone.WARN else Tone.MUTED,
                    )
                ),
            )
        }

        // ---- Speech. Second, because on this app it is the other half of a spoken translation. ----
        if (speech != null) {
            val rows = ArrayList<Row>(8)
            // Distance from 1.0, not milliseconds, and it leads the section. Recognition runs while
            // the person is still talking, so latency is not what they feel — falling behind is. A
            // phone over 1.0x accumulates backlog for as long as the speaker keeps going.
            val rt = speech.realtimeFactor
            rows += Row(
                "Realtime factor",
                "${round2(rt)}×",
                when {
                    rt >= 1.0 -> Tone.WARN
                    rt <= 0.7 -> Tone.GOOD
                    else -> Tone.NORMAL
                },
            )
            rows += Row(
                "  verdict",
                if (rt < 1.0) "keeps up with live speech" else "FALLS BEHIND live speech",
                if (rt < 1.0) Tone.MUTED else Tone.WARN,
            )
            rows += Row("Recognition", "${round1(speech.recognition.median)} ms", Tone.GOOD)
            rows += Row("p95", "${round1(speech.recognition.p95)} ms", Tone.MUTED)
            rows += Row("Audio", "${round2(speech.audioSeconds)} s")
            rows += Row("Model load", "${speech.modelLoadMs} ms", Tone.MUTED)
            // The decode width travels with the number, because it is the dial that produced it —
            // a realtime factor quoted without it is not comparable to another device's.
            speech.decodeConfig["max-active"]?.let { maxActive ->
                val beam = speech.decodeConfig["beam"] ?: "?"
                rows += Row("Decode width", "max-active $maxActive · beam $beam", Tone.MUTED)
            }
            speech.leak?.let {
                rows += Row(
                    "Leak (${it.cycles} sessions)",
                    if (it.leaking) "fds +${it.fdDelta}, threads +${it.threadDelta}"
                    else "clean · fds ${signed(it.fdDelta)}, native ${signed(it.nativeKbDelta)} KB",
                    if (it.leaking) Tone.WARN else Tone.GOOD,
                )
            }
            out += Section("Speech", rows)
        } else if (SpeechWorkload.isReady(context, Direction.EN_TO_HI) || speechError != null) {
            out += Section(
                "Speech",
                listOf(Row("Status", speechError?.let { "failed — $it" } ?: "skipped", Tone.WARN)),
            )
        } else {
            out += Section(
                "Speech",
                listOf(Row("Status", "skipped — no acoustic model staged", Tone.MUTED)),
            )
        }

        // ---- CPU kernels ----
        val kernels = ArrayList<Row>(6)
        synthetic.groupBy { it.name }.forEach { (name, runs) ->
            val single = runs.firstOrNull { it.threads == 1 }
            val multi = runs.maxByOrNull { it.threads }
            val label = KERNEL_LABELS[name] ?: name
            if (single != null && multi != null && multi.threads > 1) {
                kernels += Row(label, "${fmtOps(single.opsPerSec)} → ${fmtOps(multi.opsPerSec)}")
                kernels += Row("  ${multi.threads}-thread scaling", "${round2(multi.opsPerSec / single.opsPerSec)}×", Tone.MUTED)
            } else if (single != null) {
                kernels += Row(label, fmtOps(single.opsPerSec))
            }
        }
        if (kernels.isNotEmpty()) out += Section("CPU throughput", kernels)

        // ---- Thermal, clock and power ----
        val load = ArrayList<Row>(6)
        mt?.sustained?.let {
            val tone = if (it.degradation > 1.15) Tone.WARN else Tone.GOOD
            load += Row("Sustained ${preset.sustainedSeconds} s", "${round1(it.firstWindowMedian)} → ${round1(it.lastWindowMedian)} ms", tone)
            load += Row("  drift", "${round2(it.degradation)}×", Tone.MUTED)
        }
        (s["sustainedClockRatio"] as? Double)?.let {
            load += Row("Clock held", "${(it * 100).toInt()}% of peak", if (it < 0.8) Tone.WARN else Tone.NORMAL)
        }
        (s["thermalRiseC"] as? Double)?.let {
            load += Row("Battery temp", "+$it °C → ${s["batteryTempPeakC"]} °C", if (it > 8.0) Tone.WARN else Tone.NORMAL)
        }
        // Charge only falls while discharging. A bare "0 µAh" next to a plugged-in phone reads as a
        // broken counter, so the state that invalidates the number travels with it.
        (s["batteryDrainUah"] as? Long)?.let { drain ->
            val plugged = before.batteryPlugged
            if (plugged != null && plugged != "unplugged") {
                load += Row("Battery drain", "n/a — on $plugged", Tone.MUTED)
            } else {
                load += Row("Battery drain", "$drain µAh")
            }
        }
        if (load.isNotEmpty()) out += Section("Load & thermal", load)

        // ---- Device ----
        val device = ArrayList<Row>(7)
        // First, and stated in full: a Light run beside a Heavy one is not a comparison, and the
        // digest is what gets screenshotted into a chat window without the JSON.
        device += Row(
            "Preset",
            "${preset.label} · ${preset.mtIterations}× · ${preset.sustainedSeconds} s soak",
        )
        device += Row("Model", Build.MODEL)
        device += Row("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        device += Row("CPU", "${caps.architecture} · ${caps.coreCount} cores")
        device += Row("Clusters", "${caps.performanceCores}P / ${caps.efficiencyCores}E")
        device += Row("ISA", isaLine(caps))
        device += Row("ORT threads", "${ExecutionPolicy.current.intraThreads ?: "default"}, arena=${ExecutionPolicy.current.cpuArena}")
        before.batteryPlugged?.let { device += Row("Power", "$it · ${before.batteryLevelPct ?: "?"}%", Tone.MUTED) }
        out += Section("Device", device)

        // ---- Process ----
        val proc = ArrayList<Row>(4)
        (s["peakThreadCount"] as? Int)?.let { proc += Row("Peak threads", "$it") }
        (s["processCpuTicks"] as? Long)?.let { proc += Row("CPU time", "$it ticks") }
        val pssBefore = before.totalPssKb
        val pssAfter = after.totalPssKb
        if (pssBefore != null && pssAfter != null) {
            proc += Row("Memory (PSS)", "${pssBefore / 1024} → ${pssAfter / 1024} MB")
        }
        (s["sampleCount"] as? Int)?.let { proc += Row("Samples", "$it @ 500 ms", Tone.MUTED) }
        if (proc.isNotEmpty()) out += Section("Process", proc)

        return out
    }

    private fun isaLine(c: CpuCapabilities): String = buildString {
        val flags = listOf(
            "neon" to c.neon, "fp16" to c.fp16, "dotprod" to c.dotProduct, "i8mm" to c.i8mm,
            "sve" to c.sve, "sve2" to c.sve2, "sme" to c.sme, "sme2" to c.sme2,
        ).filter { it.second }.joinToString(" ") { it.first }
        append(flags.ifEmpty { "none detected" })
    }

    // Short unit ("G op/s" not "Gop/s per second") so a value column stays narrow enough that the
    // label beside it never has to wrap.
    private fun fmtOps(v: Double): String = "${fmtOpsValue(v)}${fmtOpsUnit(v)}"

    private fun fmtOpsValue(v: Double): String = when {
        v >= 1e9 -> round2(v / 1e9).toString()
        v >= 1e6 -> round1(v / 1e6).toString()
        else -> round1(v / 1e3).toString()
    }

    private fun fmtOpsUnit(v: Double): String = when {
        v >= 1e9 -> "G op/s"
        v >= 1e6 -> "M op/s"
        else -> "K op/s"
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    /** A drift of zero reads as an achievement only if the sign is visible; "-6" is the good news. */
    private fun signed(v: Number): String = if (v.toDouble() > 0) "+$v" else "$v"

    private companion object {
        /** Readable names for the kernels; the JSON keeps the machine-friendly ids. */
        val KERNEL_LABELS = mapOf(
            "int8_dot" to "int8 dot product",
            "fp32_gemm" to "fp32 GEMM",
            "int32_mac" to "int32 chain",
        )
    }
}
