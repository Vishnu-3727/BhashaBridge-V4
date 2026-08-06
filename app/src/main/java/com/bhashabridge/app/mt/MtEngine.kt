package com.bhashabridge.app.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Metrics
import java.nio.LongBuffer
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/**
 * Purpose:  Translates one direction's text end-to-end: tokenize → encode → decode → detokenize.
 *           The single type the rest of the app calls for machine translation; nothing above it
 *           touches ONNX Runtime, a tokenizer, or a [Decoder].
 * Owns:     One [Tokenizer] (heap) and one [OnnxModels] (native: encoder + decoder_init + decoder_step
 *           sessions). Transient per-step tensors and the per-translation KV cache are created and
 *           closed inside [translate].
 * Lifetime: Constructed and [release]d by [com.bhashabridge.app.BhashaBridgeApp] at process scope.
 * Thread:   [translate] is synchronous; run it off the main thread. Not internally synchronised —
 *           one engine per direction, one translation at a time.
 *
 * The decode strategy is injected as a [Decoder] (default [GreedyDecoder], what v3.4.1 shipped).
 * MtEngine depends only on that interface. Phase 6B moved from the uncached decoder to the verified
 * cached graphs (Phase 6A): the whole change lives in [CachedLogitsSource], behind the frozen
 * [LogitsSource] seam — no decoder code changed. The decoder still just asks "logits for this
 * prefix"; the runtime, not the decoder, owns and advances the cache.
 */
class MtEngine(
    context: Context,
    val direction: Direction,
    private val decoder: Decoder = GreedyDecoder(),
    tune: OrtTuning = ExecutionPolicy.current,
) {

    private val tokenizer: Tokenizer
    private val models: OnnxModels

    init {
        // The two dictionary parses used to run first, alone, on this thread — about a second of a
        // ~10.5 s cold start doing nothing the three session loads could not do at the same time.
        // They share nothing: the tokenizer reads two JSON files into two maps, `OnnxModels` reads
        // three graphs and builds three sessions, and neither looks at the other's output until both
        // are done. `OnnxModels` already fans out across three threads (§3.12), so this adds a
        // fourth rather than introducing concurrency to a serial load.
        val pool = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bb-tokenizer-load").apply { isDaemon = true }
        }
        try {
            val parsing = pool.submit(Callable {
                val start = System.nanoTime()
                val loaded = Tokenizer.load(context, direction)
                loaded to (System.nanoTime() - start)
            })
            models = OnnxModels(context, direction, tune)
            // Awaited unconditionally, and before anything can throw out of this block: a tokenizer
            // still parsing on a daemon thread after a failed construction would touch a context the
            // caller has already given up on.
            val (parsed, elapsedNanos) = try {
                parsing.get()
            } catch (e: ExecutionException) {
                models.release() // the sessions loaded; nothing else will ever close them
                throw e.cause ?: e
            }
            tokenizer = parsed
            // A Metrics run is thread-confined (R6.3), so the stage marks inside `Tokenizer.load`
            // now land on a worker with no active run and are dropped. The elapsed time is replayed
            // here, on the thread that owns the `engine_init` run, so the breakdown survives the move.
            Metrics.counter("tokenizer_us", elapsedNanos / 1000)
        } finally {
            pool.shutdown()
        }
    }

    /** Translates [text]. Returns the target-language string. */
    fun translate(text: String): String {
        Metrics.begin("translate")

        val srcIds = tokenizer.encode(text)
        Metrics.stage("tokenize")

        val mask = OnnxTensor.createTensor(models.env, LongBuffer.wrap(LongArray(srcIds.size) { 1L }), longArrayOf(1, srcIds.size.toLong()))
        // A throw out of the encoder run must not strand native tensors: `mask` outlives this block
        // (the decoder feeds it every step) so it is closed by hand on the failure path, and the
        // input ids tensor is scoped to the run that uses it.
        val encoderOut = try {
            OnnxTensor.createTensor(models.env, LongBuffer.wrap(srcIds), longArrayOf(1, srcIds.size.toLong()))
                .use { srcTensor ->
                    models.encoderSession().run(mapOf("input_ids" to srcTensor, "attention_mask" to mask))
                }
        } catch (e: Throwable) {
            mask.close()
            throw e // the unfinished Metrics run is discarded by the next begin() — see Metrics.beginNow
        }
        val encoderHidden = encoderOut[0] as OnnxTensor
        Metrics.stage("encoder")

        val source = CachedLogitsSource(models, encoderHidden, mask)
        try {
            val generated = decoder.decode(source, srcIds.size)
            Metrics.stage("decode")
            Metrics.counter("tokens", (generated.size - 1).toLong())
            // Cache-flow timings, kept separate from the wall-clock decode stage (µs, per the brief).
            Metrics.counter("init_us", source.initNanos / 1000)
            Metrics.counter("step_us", source.stepNanos / 1000)
            Metrics.counter("steps", source.steps.toLong())
            return tokenizer.decode(generated.copyOfRange(1, generated.size))
        } finally {
            source.close()     // releases the final present-cache tensors
            encoderOut.close() // closes encoderHidden
            mask.close()
            Metrics.end()
        }
    }

    /**
     * P8: flush ORT's per-graph profile traces and return their file paths (empty unless the engine was
     * built with [OrtTuning.profileDir]). Call after the runs to be measured, before [release].
     */
    fun endProfiling(): List<String> = models.endProfiling()

    /** Releases the native sessions. Only call site is the process-scoped owner (R4.5). */
    fun release() {
        models.release()
    }
}

/**
 * The KV-cache runtime, hidden behind [LogitsSource]. One instance per translation; it owns the cache
 * for exactly that translation and nothing outlives [close].
 *
 * The decoder hands the full prefix each step (the frozen Phase 4 contract). This maps it to the
 * cached graphs:
 *  - the first call, or any call whose prefix is not the previous prefix + one token, runs
 *    **decoder_init** on the whole prefix and rebuilds the cache from scratch;
 *  - a call that extends the previous prefix by exactly one token runs **decoder_step** with just
 *    that new token plus the retained cache.
 *
 * Greedy always takes the fast step path after the first token. A decoder that reorders or shortens
 * prefixes (e.g. beam) simply falls back to init — correct, just not accelerated, which is fine: this
 * phase integrates the cache, it does not tune it.
 *
 * Native lifetime: each run's [OrtSession.Result] holds the `present` cache tensors. They are fed as
 * `past_key_values` into the next run, so the previous Result is kept open until the next one
 * supersedes it, then closed. [close] releases the last one.
 */
private class CachedLogitsSource(
    private val models: OnnxModels,
    private val encoderHidden: OnnxTensor,
    private val mask: OnnxTensor,
) : LogitsSource {

    private var prevPrefix: LongArray? = null
    private var pastCache: OrtSession.Result? = null

    var initNanos = 0L; private set
    var stepNanos = 0L; private set
    var steps = 0; private set

    override fun nextLogits(prefix: LongArray): FloatArray {
        val prev = prevPrefix
        val extendsPrev = prev != null && pastCache != null &&
            prefix.size == prev.size + 1 && prefixMatches(prefix, prev)

        val result = if (extendsPrev) runStep(prefix.last()) else runInit(prefix)

        pastCache?.close()   // free the superseded cache (no-op on first init)
        pastCache = result
        prevPrefix = prefix
        return lastLogitsRow(result)
    }

    /**
     * decoder_init: full prefix in, logits + fresh present out. Rebuilds the cache.
     *
     * `use` rather than a trailing `close()`: a throw out of `run` would otherwise skip the close,
     * and `translate`'s `finally` cannot reach this tensor — it only knows about the mask, the
     * encoder output and the cache. One leaked input tensor per failed step.
     */
    private fun runInit(prefix: LongArray): OrtSession.Result =
        OnnxTensor.createTensor(models.env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong()))
            .use { decIds ->
                val t0 = System.nanoTime()
                val result = models.decoderInitSession().run(
                    mapOf(
                        "decoder_input_ids" to decIds,
                        "encoder_hidden_states" to encoderHidden,
                        "encoder_attention_mask" to mask,
                    )
                )
                initNanos += System.nanoTime() - t0
                result
            }

    /**
     * decoder_step: one new token + the retained cache in, logits + grown present out. No
     * `encoder_hidden_states` — the graph reuses cached cross-attn K/V (Phase 6A). Past tensors are
     * fed by name from the previous run's outputs, `pastInputNames[i]` ← output `i + 1` (output 0 is
     * logits), which is the verified cache ordering.
     */
    private fun runStep(newToken: Long): OrtSession.Result {
        val past = pastCache!!
        // `use`, for the same reason as runInit: this is the per-token path, so a failure here
        // without it leaks one input tensor for every step already taken.
        return OnnxTensor.createTensor(models.env, LongBuffer.wrap(longArrayOf(newToken)), longArrayOf(1, 1))
            .use { decIds ->
                val feed = HashMap<String, OnnxTensor>(models.pastInputNames.size + 2)
                feed["decoder_input_ids"] = decIds
                feed["encoder_attention_mask"] = mask
                models.pastInputNames.forEachIndexed { i, name ->
                    feed[name] = past[i + 1] as OnnxTensor
                }
                val t0 = System.nanoTime()
                val result = models.decoderStepSession().run(feed)
                stepNanos += System.nanoTime() - t0
                steps++
                result
            }
    }

    /** Reads logits for the last position from a run's output 0, shape `[1, dec_len, vocab]`. */
    private fun lastLogitsRow(result: OrtSession.Result): FloatArray =
        lastLogitsRow(result[0] as OnnxTensor)

    /** True if [prefix] starts with all of [prev] (prev is prefix's leading slice). */
    private fun prefixMatches(prefix: LongArray, prev: LongArray): Boolean {
        for (i in prev.indices) if (prefix[i] != prev[i]) return false
        return true
    }

    fun close() {
        pastCache?.close()
        pastCache = null
    }
}

/**
 * The last position's logits row out of a `[1, dec_len, vocab]` output tensor — one row of 122,672
 * floats for EN→HI, read once per generated token, on the critical path.
 *
 * This used to be `(tensor.value as Array<Array<FloatArray>>)[0].last().copyOf()`, which copies the
 * data **twice**: `value` materialises the whole output as boxed nested Java arrays, then `copyOf`
 * duplicates the one surviving row. None of it shows up in ORT's operator profiler, which sees kernel
 * time only — it lands in the 21% "outside kernels" bucket entry #9 measured and could not attribute
 * (bench/results/cross-device/S26U_EXPERIMENTS.md §1).
 *
 * **`getFloatBuffer()` is not a zero-copy view** — worth stating, because the name suggests otherwise
 * and the plan for this change assumed it. Disassembling ORT 1.27.0's `OnnxTensor` shows it does
 * `FloatBuffer.allocate(capacity)` then `put(nativeView)`: a full heap copy of the entire tensor.
 * Swapping `value` for it, on its own, buys nothing.
 *
 * What it does buy is the *second* copy, and only because that heap buffer is array-backed. When
 * `dec_len == 1` — every `decoder_step` call, i.e. all but the first token of every translation — the
 * buffer's backing array *is* the row, so [java.nio.FloatBuffer.array] hands it over with nothing
 * further copied: two full-width copies per token become one. `dec_len > 1` (`decoder_init`, once per
 * translation) still needs the slice.
 *
 * The returned array must stay mutable and unshared — [applyRepetitionPenalty] and
 * [blockRepeatedNgrams] write into it in place. Both branches satisfy that: `allocate` produced the
 * backing array fresh inside `getFloatBuffer`, and nothing else holds a reference to it.
 *
 * Batch is assumed to be 1, as it is everywhere in this runtime — one translation at a time, never a
 * batched fan-out. Both branches read `shape[1]`/`shape[2]` only, so a batched tensor would be wrong
 * in either; the assumption belongs to the engine, not to this function.
 *
 * ponytail: the real lever is upstream. `decoder_init` computes and returns logits for every prefix
 * position and the runtime discards all but the last, so the copy is proportional to a prefix nothing
 * reads. Slicing the last position inside the exported graph would shrink the ORT-side allocation too,
 * not just this copy — a model_pipeline re-export, tracked as a finding rather than done here.
 */
internal fun lastLogitsRow(tensor: OnnxTensor): FloatArray {
    val shape = tensor.info.shape               // [1, dec_len, vocab]
    val buffer = tensor.floatBuffer
    if (shape[1] == 1L && buffer.hasArray() && buffer.arrayOffset() == 0) return buffer.array()
    val vocab = shape[2].toInt()
    val row = FloatArray(vocab)
    buffer.duplicate().apply { position((shape[1].toInt() - 1) * vocab) }.get(row)
    return row
}
