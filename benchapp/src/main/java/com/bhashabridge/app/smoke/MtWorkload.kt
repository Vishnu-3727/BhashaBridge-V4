package com.bhashabridge.app.smoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.mt.ExecutionPolicy
// The shipping selection and logits-row reader, not copies — both are internal to the mt package,
// which this module compiles, so the stage-level decode is the same arithmetic the app performs.
import com.bhashabridge.app.mt.argmax
import com.bhashabridge.app.mt.lastLogitsRow
import com.bhashabridge.app.mt.MtEngine
import com.bhashabridge.app.mt.OnnxModels
import com.bhashabridge.app.mt.OrtTuning
import com.bhashabridge.app.mt.Tokenizer
import java.io.File
import java.nio.LongBuffer

/**
 * Purpose:  The real half of the smoke test — the shipping INT8 graphs, measured stage by stage, so
 *           "encode", "decode", "tokens in/out" mean the actual transformer and not a stand-in.
 * Owns:     One [OnnxModels] and one [Tokenizer] per [run]; both released before returning.
 * Lifetime: One run.
 * Thread:   Blocking. Call off the main thread.
 *
 * Two levels of measurement, because they answer different questions:
 *
 *  - **End-to-end** `MtEngine.translate()` — the number a user feels, through the exact code path
 *    the app ships. Nothing here reimplements it.
 *  - **Per-stage** encoder / decoder_init / decoder_step, driven directly against the same sessions.
 *    This is the split the end-to-end number cannot give, and it is where an Arm regression actually
 *    shows: a device slow in `decoder_step` but fine in `encoder` is a small-GEMM and threading
 *    problem, not a bandwidth one.
 *
 * `optCache = false` is deliberate. The production loader bakes an ALL_OPT `.ort` once per install
 * and mmaps it NO_OPT afterwards; that changes **load** time, not the graph that executes, since the
 * uncached path optimises to the same level on the way in. Turning it off keeps every run's session
 * build identical and avoids a first-run/second-run split in a report meant to compare devices. Every
 * other knob — thread count, arena, affinity — is [ExecutionPolicy.current], i.e. exactly production.
 */
class MtWorkload(
    private val context: Context,
    private val direction: Direction = Direction.EN_TO_HI,
) {

    class Result(
        val policyName: String,
        val intraThreads: Int?,
        val affinity: String?,
        val sessionLoadMs: Long,
        val tokensIn: Int,
        val tokensOut: Int,
        val endToEnd: Stats,
        val encoderMs: Stats,
        val decoderInitMs: Stats,
        val decoderStepMs: Stats,
        val tokensPerSec: Double,
        val sampleInput: String,
        val sampleOutput: String,
        val kleidiAi: KleidiResult?,
        val sustained: SustainedResult?,
    )

    /** The KleidiAI A/B: what Arm's INT8 microkernels are actually worth on this silicon. */
    class KleidiResult(val onMs: Double, val offMs: Double) {
        /** >1 means KleidiAI is faster. 1.0 means it did not dispatch on this core. */
        val speedup: Double get() = if (onMs > 0) offMs / onMs else 0.0
    }

    /** Latency drift across a long run — cold burst versus what the device actually sustains. */
    class SustainedResult(
        val windowMedians: List<Double>,
        val firstWindowMedian: Double,
        val lastWindowMedian: Double,
    ) {
        /** >1 means it got slower. This is throttling, expressed as latency rather than clock. */
        val degradation: Double get() = if (firstWindowMedian > 0) lastWindowMedian / firstWindowMedian else 0.0
    }

    private val short = "Water."
    private val long = "The weather is very nice today and I want to go outside."

    fun run(
        iterations: Int,
        sustainedSeconds: Int,
        includeKleidiAb: Boolean,
        onProgress: (String) -> Unit,
    ): Result {
        onProgress("staging models")
        ModelStore.stage(context, direction)

        val tuning = ExecutionPolicy.current.copy(optCache = false)

        onProgress("loading sessions")
        val loadStart = System.nanoTime()
        // The shipping loader: `ModelStore.stage` put the vocabularies in filesDir, which
        // `Tokenizer.load` prefers over assets — so this is the app's own tokenizer, not a copy.
        val tokenizer = Tokenizer.load(context, direction)
        val models = OnnxModels(context, direction, tuning)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000

        try {
            // Stages first: it decodes to EOS and so yields the real generated-token count, which
            // the throughput figure below needs. Re-encoding the Hindi output with the *source*
            // (English) vocabulary was the obvious-looking way to count output tokens and is wrong —
            // every Devanagari word misses and falls to per-character `<unk>`, which reported 54
            // tokens for a 12-token translation and inflated tokens/second by the same factor.
            onProgress("per-stage encoder / decoder")
            val stages = measureStages(models, tokenizer, iterations)

            onProgress("warming up")
            val engine = MtEngine(context, direction, tune = tuning)
            val e2e: Stats
            val tokensPerSec: Double
            val sampleOut: String
            try {
                repeat(3) { engine.translate(short); engine.translate(long) }

                onProgress("end-to-end · $iterations iterations")
                val latencies = ArrayList<Long>(iterations)
                var last = ""
                repeat(iterations) {
                    val t = System.nanoTime()
                    last = engine.translate(long)
                    latencies += (System.nanoTime() - t) / 1_000_000
                }
                e2e = Stats.of(latencies)
                // Same sentence every iteration, so the token count is constant and pairing it with
                // each latency is exact rather than an average of averages.
                tokensPerSec = Stats.tokensPerSec(
                    List(latencies.size) { stages.generatedTokens }, latencies,
                )
                sampleOut = last
            } finally {
                engine.release()
            }

            val sustained = if (sustainedSeconds > 0) {
                onProgress("sustained load · ${sustainedSeconds}s")
                measureSustained(tuning, sustainedSeconds, onProgress)
            } else null

            val kleidi = if (includeKleidiAb) {
                onProgress("KleidiAI A/B")
                measureKleidiAb(tokenizer)
            } else null

            return Result(
                policyName = tuning.name,
                intraThreads = tuning.intraThreads,
                affinity = tuning.intraOpAffinities,
                sessionLoadMs = loadMs,
                tokensIn = tokenizer.encode(long).size,
                tokensOut = stages.generatedTokens,
                endToEnd = e2e,
                encoderMs = stages.encoder,
                decoderInitMs = stages.init,
                decoderStepMs = stages.step,
                tokensPerSec = tokensPerSec,
                sampleInput = long,
                sampleOutput = sampleOut,
                kleidiAi = kleidi,
                sustained = sustained,
            )
        } finally {
            models.release()
        }
    }

    // ---- per-stage ------------------------------------------------------------------------------

    private class Stages(val encoder: Stats, val init: Stats, val step: Stats, val generatedTokens: Int)

    /**
     * Times the three graphs separately against one encoder output.
     *
     * `decoder_step` is measured across a *growing* cache, one call per generated position, because
     * that is how it runs in production — its cost rises with sequence length, and a single call at
     * length 1 would flatter the device. Every tensor is closed on both paths; a benchmark that
     * leaks is a benchmark that measures the allocator's death spiral.
     */
    private fun measureStages(models: OnnxModels, tokenizer: Tokenizer, iterations: Int): Stages {
        val srcIds = tokenizer.encode(long)
        val shape = longArrayOf(1, srcIds.size.toLong())
        val encoderMs = ArrayList<Long>(iterations)
        val initMs = ArrayList<Long>(iterations)
        val stepMs = ArrayList<Long>(iterations * 16)
        var generated = 0

        OnnxTensor.createTensor(models.env, LongBuffer.wrap(LongArray(srcIds.size) { 1L }), shape).use { mask ->
            repeat(iterations) {
                val t0 = System.nanoTime()
                val encoded = OnnxTensor.createTensor(models.env, LongBuffer.wrap(srcIds), shape).use { src ->
                    models.encoderSession().run(mapOf("input_ids" to src, "attention_mask" to mask))
                }
                encoderMs += (System.nanoTime() - t0) / 1_000_000

                encoded.use {
                    val hidden = encoded[0] as OnnxTensor

                    val t1 = System.nanoTime()
                    var cache = runInit(models, hidden, mask, longArrayOf(START_TOKEN))
                    initMs += (System.nanoTime() - t1) / 1_000_000

                    try {
                        // Decode for real — greedy argmax, fed back, stopping on EOS — rather than a
                        // fixed number of steps with arbitrary token ids. Two things come out of
                        // that: the per-token cost over a genuinely growing cache, and the true
                        // number of tokens the model generates for this sentence, which is the only
                        // honest denominator for tokens/second.
                        var next = argmax(lastLogitsRow(cache[0] as OnnxTensor)).toLong()
                        var steps = 0
                        while (next != EOS_TOKEN && steps < MAX_DECODE_STEPS) {
                            val t2 = System.nanoTime()
                            val out = runStep(models, mask, cache, next)
                            stepMs += (System.nanoTime() - t2) / 1_000_000
                            cache.close()
                            cache = out
                            next = argmax(lastLogitsRow(cache[0] as OnnxTensor)).toLong()
                            steps++
                        }
                        generated = steps
                    } finally {
                        cache.close()
                    }
                }
            }
        }
        return Stages(Stats.of(encoderMs), Stats.of(initMs), Stats.of(stepMs), generated)
    }

    private fun runInit(
        models: OnnxModels, hidden: OnnxTensor, mask: OnnxTensor, prefix: LongArray,
    ): OrtSession.Result =
        OnnxTensor.createTensor(models.env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong()))
            .use { decIds ->
                models.decoderInitSession().run(
                    mapOf(
                        "decoder_input_ids" to decIds,
                        "encoder_hidden_states" to hidden,
                        "encoder_attention_mask" to mask,
                    )
                )
            }

    /** Feeds `pastInputNames[i]` from output `i + 1` — the cache ordering `OnnxModels` verifies. */
    private fun runStep(
        models: OnnxModels, mask: OnnxTensor, past: OrtSession.Result, token: Long,
    ): OrtSession.Result =
        OnnxTensor.createTensor(models.env, LongBuffer.wrap(longArrayOf(token)), longArrayOf(1, 1))
            .use { decIds ->
                val feed = HashMap<String, OnnxTensor>(models.pastInputNames.size + 2)
                feed["decoder_input_ids"] = decIds
                feed["encoder_attention_mask"] = mask
                models.pastInputNames.forEachIndexed { i, name -> feed[name] = past[i + 1] as OnnxTensor }
                models.decoderStepSession().run(feed)
            }

    // ---- sustained ------------------------------------------------------------------------------

    /**
     * Translates continuously for [seconds], reporting the median of each 10-second window.
     *
     * A burst benchmark on a cold phone measures the boost clock. This measures what the device
     * still delivers once its thermal budget is spent, which for an offline translator in someone's
     * hand is the number that decides whether the app feels fast. The windows are medians, not
     * means, so one scheduling hiccup does not become the story.
     */
    private fun measureSustained(
        tuning: OrtTuning, seconds: Int, onProgress: (String) -> Unit,
    ): SustainedResult {
        val engine = MtEngine(context, direction, tune = tuning)
        try {
            val windowMs = 10_000L
            val deadline = System.currentTimeMillis() + seconds * 1000L
            val windows = ArrayList<MutableList<Long>>()
            var windowEnd = System.currentTimeMillis() + windowMs
            windows += ArrayList<Long>()

            while (System.currentTimeMillis() < deadline) {
                val t = System.nanoTime()
                engine.translate(long)
                windows.last() += (System.nanoTime() - t) / 1_000_000
                if (System.currentTimeMillis() >= windowEnd) {
                    val remain = (deadline - System.currentTimeMillis()) / 1000
                    onProgress("sustained load · ${remain}s left")
                    windows += ArrayList<Long>()
                    windowEnd += windowMs
                }
            }
            val medians = windows.filter { it.isNotEmpty() }.map { Stats.of(it).median }
            return SustainedResult(
                windowMedians = medians,
                firstWindowMedian = medians.firstOrNull() ?: 0.0,
                lastWindowMedian = medians.lastOrNull() ?: 0.0,
            )
        } finally {
            engine.release()
        }
    }

    // ---- ISA probe ------------------------------------------------------------------------------

    /**
     * The one ISA claim this app can actually prove.
     *
     * `/proc/cpuinfo` says whether a core *has* i8mm or SME; it says nothing about whether the code
     * that ran used them. ORT's MLAS dispatches KleidiAI microkernels on capable silicon with no
     * configuration, and the dispatch is invisible to ORT's own operator profiler, which sees kernel
     * time only. Building the same sessions twice — once with `mlas.disable_kleidiai` — and diffing
     * the decode time is the only measurement from Java that attributes the gain, and it is the same
     * method `bench/results/cross-device/` used to price SME at 4–9% rather than the 2× first assumed.
     *
     * A ratio of ~1.0 is a real result, not a failure: it means this core has no microkernel path
     * for these shapes, which is exactly what a smoke test should report.
     */
    private fun measureKleidiAb(tokenizer: Tokenizer): KleidiResult {
        val base = ExecutionPolicy.current.copy(optCache = false)
        val on = timeDecode(base.copy(name = "kleidi-on", disableKleidiAi = false))
        val off = timeDecode(base.copy(name = "kleidi-off", disableKleidiAi = true))
        return KleidiResult(onMs = on, offMs = off)
    }

    /** Median translate() latency for one tuning, on its own freshly-built sessions. */
    private fun timeDecode(tuning: OrtTuning): Double {
        val engine = MtEngine(context, direction, tune = tuning)
        return try {
            repeat(3) { engine.translate(long) }
            Stats.of((0 until 10).map {
                val t = System.nanoTime()
                engine.translate(long)
                (System.nanoTime() - t) / 1_000_000
            }).median
        } finally {
            engine.release()
        }
    }

    private companion object {
        /** IndicTrans2 uses one `</s>` id as both start and stop; see `DecodeConfig`. */
        const val START_TOKEN = 2L
        const val EOS_TOKEN = 2L

        /** Runaway guard for the stage-level decode loop, matching `DecodeConfig.maxSteps`. */
        const val MAX_DECODE_STEPS = 128
    }
}
