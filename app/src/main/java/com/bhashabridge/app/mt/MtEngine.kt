package com.bhashabridge.app.mt

import ai.onnxruntime.OnnxTensor
import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Metrics
import java.nio.LongBuffer

/**
 * Purpose:  Translates one direction's text end-to-end: tokenize → encode → decode → detokenize.
 *           The single type the rest of the app calls for machine translation; nothing above it
 *           touches ONNX Runtime, a tokenizer, or a [Decoder].
 * Owns:     One [Tokenizer] (heap) and one [OnnxModels] (native). Transient per-step tensors are
 *           created and closed inside [translate].
 * Lifetime: Constructed and [release]d by [com.bhashabridge.app.BhashaBridgeApp] at process scope.
 * Thread:   [translate] is synchronous; run it off the main thread. Not internally synchronised —
 *           one engine per direction, one translation at a time.
 *
 * The decode strategy is injected as a [Decoder] (default [GreedyDecoder], what v3.4.1 shipped).
 * MtEngine depends only on that interface: swapping in beam is a constructor argument, and the
 * Phase 5-forbidden KV-cache work will change [logitsFor] alone, not this flow.
 */
class MtEngine(
    context: Context,
    val direction: Direction,
    private val decoder: Decoder = GreedyDecoder(),
) {

    private val tokenizer = Tokenizer.load(context, direction)
    private val models = OnnxModels(context, direction)

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
        Metrics.stage("encode")

        try {
            val source = LogitsSource { prefix -> logitsFor(prefix, encoderHidden, mask) }
            val generated = decoder.decode(source, srcIds.size)
            Metrics.stage("decode")
            Metrics.counter("tokens", (generated.size - 1).toLong())
            return tokenizer.decode(generated.copyOfRange(1, generated.size))
        } finally {
            encoderOut.close() // closes encoderHidden
            mask.close()
            Metrics.end()
        }
    }

    /**
     * One decoder forward pass. Feeds the whole prefix (the uncached graph has no other mode —
     * `model_pipeline/MODEL_PIPELINE.md` §4) and returns logits for the last position only.
     *
     * `OnnxTensor.value` materialises the full `[1, seq, vocab]` result as boxed arrays — the
     * ~4.6 MB/step allocation flagged in the engineering plan. Left as-is on purpose: it matches
     * v3.4.1 exactly for the parity check, and the zero-copy `getFloatBuffer` fix is an optimisation
     * this phase does not make.
     */
    private fun logitsFor(prefix: LongArray, encoderHidden: OnnxTensor, mask: OnnxTensor): FloatArray {
        val decTensor = OnnxTensor.createTensor(models.env, LongBuffer.wrap(prefix), longArrayOf(1, prefix.size.toLong()))
        val out = models.decoderSession().run(
            mapOf(
                "input_ids" to decTensor,
                "encoder_hidden_states" to encoderHidden,
                "encoder_attention_mask" to mask,
            )
        )
        try {
            @Suppress("UNCHECKED_CAST")
            val logits = (out[0] as OnnxTensor).value as Array<Array<FloatArray>>
            return logits[0].last().copyOf()
        } finally {
            decTensor.close()
            out.close()
        }
    }

    /** Releases the native sessions. Only call site is the process-scoped owner (R4.5). */
    fun release() {
        models.release()
    }
}
