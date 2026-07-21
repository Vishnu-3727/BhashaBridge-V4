package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bhashabridge.app.Direction
import java.io.File
import java.io.FileOutputStream

/**
 * Purpose:  Owns the three KV-cache [OrtSession]s for one [Direction] — encoder, decoder_init,
 *           decoder_step — and the one-time copy of their `.onnx` assets to app-private storage
 *           (ONNX Runtime loads from a file path).
 * Owns:     Three native `OrtSession`s. The [OrtEnvironment] is a process-wide singleton per ONNX
 *           Runtime's contract and is NOT closed here.
 * Lifetime: Created and released by [com.bhashabridge.app.BhashaBridgeApp] at process scope. Nothing
 *           else may construct or release it (R4.4). This is the fix for v3.4.1's L2 leak: one
 *           owner, one [release], a real call site.
 * Thread:   Construction blocks until all three sessions load. `run()` on the exposed sessions is
 *           synchronous on the caller's thread.
 *
 * Phase 6B replaced the single uncached decoder with the verified cached pair from Phase 6A:
 * decoder_init (first step, builds the cache) then decoder_step (later steps, cache in and out).
 * decoder_step has NO `encoder_hidden_states` input — with past present the graph reuses cached
 * cross-attn K/V, so torch.onnx pruned it (see model_pipeline/EXPORT_WITH_CACHE.md). The runtime
 * must feed the cache back each step, never that input.
 *
 * Session options are left at ONNX Runtime defaults — no thread-count, XNNPACK, or NEON tuning.
 * This phase is correctness only; runtime tuning is a later phase and must be measured, not guessed.
 */
class OnnxModels(context: Context, direction: Direction) {

    val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val encoder: OrtSession
    private val decoderInit: OrtSession
    private val decoderStep: OrtSession

    /**
     * The decoder_step graph's `past_key_values.*` input names, in graph order, which is exactly the
     * order of decoder_init/decoder_step's `present.*` outputs after the leading `logits`. Read from
     * the model rather than hard-coded, so it stays correct for any layer count and cannot drift from
     * the exported cache ordering (the Phase 6A contract). `CachedLogitsSource` feeds
     * `pastInputNames[i]` from output `i + 1` of the previous run.
     */
    val pastInputNames: List<String>

    init {
        val (encAsset, initAsset, stepAsset) = when (direction) {
            // Phase 6D: the verified INT8 cached graphs (Phase 6C), kept as the production path —
            // the benchmark showed the cache is a clear win at real output lengths (docs/CACHE_BENCHMARK.md).
            Direction.EN_TO_HI ->
                Triple("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")
            // HI->EN cached graphs are not yet exported (Phase 6A did en_hi only; R-PROV). Naming is
            // fixed here so the export, when it lands, drops straight in.
            Direction.HI_TO_EN ->
                Triple("hi_en_encoder.onnx", "hi_en_decoder_init.onnx", "hi_en_decoder_step.onnx")
        }
        // Sequential, not parallel: parallel load is a startup optimisation this phase does not do.
        encoder = env.createSession(copyToFiles(context, encAsset))
        decoderInit = env.createSession(copyToFiles(context, initAsset))
        decoderStep = env.createSession(copyToFiles(context, stepAsset))

        // Everything on the step graph that is not the two non-cache inputs is a cache tensor.
        pastInputNames = decoderStep.inputInfo.keys.filter { it !in NON_CACHE_STEP_INPUTS }
        val presentCount = decoderInit.outputInfo.size - 1 // minus logits
        require(pastInputNames.size == presentCount) {
            "cache mismatch: step has ${pastInputNames.size} past inputs, init emits $presentCount present"
        }
    }

    fun encoderSession(): OrtSession = encoder
    fun decoderInitSession(): OrtSession = decoderInit
    fun decoderStepSession(): OrtSession = decoderStep

    /** Closes all three native sessions. Its only call site is the process-scoped owner. */
    fun release() {
        encoder.close()
        decoderInit.close()
        decoderStep.close()
    }

    /** Idempotent asset→filesDir copy; skips if present. 1 MB buffer for the fp32 files (~800 MB). */
    private fun copyToFiles(context: Context, name: String): String {
        val file = File(context.filesDir, name)
        if (!file.exists()) {
            val buf = ByteArray(1 shl 20)
            context.assets.open(name).use { input ->
                FileOutputStream(file).use { output ->
                    var n = input.read(buf)
                    while (n != -1) { output.write(buf, 0, n); n = input.read(buf) }
                }
            }
        }
        return file.absolutePath
    }

    private companion object {
        val NON_CACHE_STEP_INPUTS = setOf("decoder_input_ids", "encoder_attention_mask")
    }
}
