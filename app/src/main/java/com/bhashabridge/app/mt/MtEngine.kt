package com.bhashabridge.app.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Metrics
import java.nio.LongBuffer

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

    private val tokenizer = Tokenizer.load(context, direction)
    private val models = OnnxModels(context, direction, tune)

    /** Translates [text]. Returns the target-language string. */
    fun translate(text: String): String {
        Metrics.begin("translate")

        val srcIds = tokenizer.encode(text)
        Metrics.stage("tokenize")

        val mask = OnnxTensor.createTensor(models.env, LongBuffer.wrap(LongArray(srcIds.size) { 1L }), longArrayOf(1, srcIds.size.toLong()))
        val srcTensor = OnnxTensor.createTensor(models.env, LongBuffer.wrap(srcIds), longArrayOf(1, srcIds.size.toLong()))
        val encoderOut = models.encoderSession().run(mapOf("input_ids" to srcTensor, "attention_mask" to mask))
        srcTensor.close()
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

    /** decoder_init: full prefix in, logits + fresh present out. Rebuilds the cache. */
    private fun runInit(prefix: LongArray): OrtSession.Result {
        val decIds = OnnxTensor.createTensor(models.env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong()))
        val t0 = System.nanoTime()
        val result = models.decoderInitSession().run(
            mapOf(
                "decoder_input_ids" to decIds,
                "encoder_hidden_states" to encoderHidden,
                "encoder_attention_mask" to mask,
            )
        )
        initNanos += System.nanoTime() - t0
        decIds.close()
        return result
    }

    /**
     * decoder_step: one new token + the retained cache in, logits + grown present out. No
     * `encoder_hidden_states` — the graph reuses cached cross-attn K/V (Phase 6A). Past tensors are
     * fed by name from the previous run's outputs, `pastInputNames[i]` ← output `i + 1` (output 0 is
     * logits), which is the verified cache ordering.
     */
    private fun runStep(newToken: Long): OrtSession.Result {
        val past = pastCache!!
        val decIds = OnnxTensor.createTensor(models.env, LongBuffer.wrap(longArrayOf(newToken)), longArrayOf(1, 1))
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
        decIds.close()
        return result
    }

    /** Reads logits for the last position from a run's output 0, shape `[1, dec_len, vocab]`. */
    private fun lastLogitsRow(result: OrtSession.Result): FloatArray {
        @Suppress("UNCHECKED_CAST")
        val logits = (result[0] as OnnxTensor).value as Array<Array<FloatArray>>
        return logits[0].last().copyOf()
    }

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
