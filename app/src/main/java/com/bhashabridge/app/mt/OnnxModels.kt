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
 * Session options come from [tune] (Phase 7). The default [OrtTuning] leaves every knob at ONNX
 * Runtime's own defaults, i.e. the exact behaviour before Phase 7; the benchmark-selected production
 * config is set as that default once measured (see docs/ORT_TUNING.md). No XNNPACK/NEON/SME here.
 */
class OnnxModels(
    context: Context,
    direction: Direction,
    private val tune: OrtTuning = OrtTuning.production(),
) {

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
        // Each session gets its own SessionOptions instance (ORT copies settings at createSession).
        encoder = env.createSession(copyToFiles(context, encAsset), tune.toOptions())
        decoderInit = env.createSession(copyToFiles(context, initAsset), tune.toOptions())
        decoderStep = env.createSession(copyToFiles(context, stepAsset), tune.toOptions())

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

/**
 * ONNX Runtime `SessionOptions` for the three sessions, one knob per field (Phase 7). A `null` field
 * means "leave ORT's own default", so the no-arg [OrtTuning] reproduces pre-Phase-7 behaviour exactly.
 * Each knob is varied independently by the tuning benchmark; the winning combination is baked as the
 * default here once measured. Purely execution config — it never touches weights, the cache, or decode.
 */
data class OrtTuning(
    val name: String = "baseline",
    val optLevel: OrtSession.SessionOptions.OptLevel? = null,
    val intraThreads: Int? = null,
    val interThreads: Int? = null,
    val parallel: Boolean = false,
    val cpuArena: Boolean? = null,
    val memPattern: Boolean? = null,
) {
    /** Build a fresh SessionOptions with only the non-null knobs applied. */
    fun toOptions(): OrtSession.SessionOptions {
        val o = OrtSession.SessionOptions()
        optLevel?.let { o.setOptimizationLevel(it) }
        intraThreads?.let { o.setIntraOpNumThreads(it) }
        interThreads?.let { o.setInterOpNumThreads(it) }
        if (parallel) o.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.PARALLEL)
        cpuArena?.let { o.setCPUArenaAllocator(it) }
        memPattern?.let { o.setMemoryPatternOptimization(it) }
        return o
    }

    companion object {
        /**
         * The Phase 7 benchmark-selected production config (docs/ORT_TUNING.md), two independently
         * evidenced knobs on the SM-M315F:
         *  - `intraThreads = 2`: pins intra-op work to the two big cores — decode ~10% faster and,
         *    more importantly, variance collapses (stdev 97→15 ms, p95 −30%) vs ORT's default which
         *    spreads onto the little cores and jitters.
         *  - `cpuArena = false`: −37% process memory (983→617 MB PSS) at no measurable latency cost;
         *    the arena's up-front pool is pure overhead for this steady, single-translation-at-a-time
         *    workload.
         */
        fun production() = OrtTuning(name = "production", intraThreads = 2, cpuArena = false)
    }
}
