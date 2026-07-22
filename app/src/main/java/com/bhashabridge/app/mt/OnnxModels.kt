package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Metrics
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

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
 *
 * Phase 11C loads the three sessions **concurrently** (docs/PARALLEL_SESSION_INITIALIZATION.md). The
 * constructor still blocks until all three exist, so every consumer sees the same fully-built object
 * it always did; only the wall-clock cost of building it changed. Nothing about inference, the cache
 * contract, or session ownership moved.
 */
class OnnxModels(
    context: Context,
    direction: Direction,
    private val tune: OrtTuning = ExecutionPolicy.current,
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
            // Phase 12: the mirror export, same pipeline, same three graphs, from
            // ai4bharat/indictrans2-indic-en-dist-200M (docs/HI_EN_IMPLEMENTATION.md). Identical
            // config — 18 layers, 8 heads, 512 hidden — so the cache contract below is the same 72
            // tensors and nothing else in this class is direction-aware. R-PROV is closed.
            Direction.HI_TO_EN ->
                Triple("hi_en_encoder_int8.onnx", "hi_en_decoder_init_int8.onnx", "hi_en_decoder_step_int8.onnx")
        }
        // Phase 11C: the three graphs load concurrently. They are independent inputs — nothing flows
        // between them at load time — and Phase 11A measured the serial cost at 12.3 s against 6.3 s
        // for the same three loads on three threads. Each task owns its own SessionOptions (ORT reads
        // settings at createSession) and its own file handle; the only shared object is [env], which
        // is a process-wide singleton created above, before any task starts.
        val loads = loadSessionsConcurrently(
            context,
            listOf(encAsset to "encoder", initAsset to "decoder_init", stepAsset to "decoder_step"),
        )
        encoder = loads.getValue("encoder").session
        decoderInit = loads.getValue("decoder_init").session
        decoderStep = loads.getValue("decoder_step").session
        Metrics.stage("sessions:parallel")
        loads.values.forEach { it.report() }

        // Everything on the step graph that is not the two non-cache inputs is a cache tensor.
        pastInputNames = decoderStep.inputInfo.keys.filter { it !in NON_CACHE_STEP_INPUTS }
        val presentCount = decoderInit.outputInfo.size - 1 // minus logits
        require(pastInputNames.size == presentCount) {
            "cache mismatch: step has ${pastInputNames.size} past inputs, init emits $presentCount present"
        }
        Metrics.stage("cache_contract")
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

    /**
     * Loads every `(asset, label)` pair on its own thread and returns them keyed by label, once all
     * have finished. Blocks the calling thread — the caller is already the process-scoped engine
     * construction, which must not report ready until every session exists.
     *
     * Failure handling is the reason this is not three bare threads:
     *  - **Every** future is awaited before anything is thrown, so no task is left running against a
     *    half-constructed object.
     *  - If any task failed, the sessions that *did* load are closed before the exception leaves this
     *    method. A partially-loaded engine would otherwise leak hundreds of MB of native memory with
     *    no owner to release it (LESSONS_FROM_V3 L2, in a new disguise).
     *  - The original exception is rethrown with its cause unwrapped from [ExecutionException], so
     *    callers see the same `OrtException` / `IOException` a serial load would have produced.
     *  - The pool is shut down in a `finally`, so no thread outlives this call on any path.
     */
    private fun loadSessionsConcurrently(
        context: Context,
        assets: List<Pair<String, String>>,
    ): Map<String, SessionLoad> {
        val pool = Executors.newFixedThreadPool(assets.size) { runnable ->
            Thread(runnable, "bb-session-load").apply { isDaemon = true }
        }
        try {
            val futures = assets.map { (asset, label) ->
                pool.submit(Callable { loadSession(context, asset, label) })
            }
            val loaded = ArrayList<SessionLoad>(futures.size)
            var failure: Throwable? = null
            for (future in futures) {
                try {
                    loaded += future.get()
                } catch (e: ExecutionException) {
                    if (failure == null) failure = e.cause ?: e
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (failure == null) failure = e
                }
            }
            failure?.let { cause ->
                loaded.forEach { runCatching { it.session.close() } }
                throw IllegalStateException("ONNX session load failed", cause)
            }
            return loaded.associateBy { it.label }
        } finally {
            pool.shutdown()
        }
    }

    /** One graph: resolve its file, then build its session. Runs entirely on one worker thread. */
    private fun loadSession(context: Context, name: String, label: String): SessionLoad {
        val file = File(context.filesDir, name)
        val verifyStart = System.nanoTime()
        val present = file.exists()
        val verifyNs = System.nanoTime() - verifyStart

        var extractNs = 0L
        if (!present) {
            val extractStart = System.nanoTime()
            val buf = ByteArray(1 shl 20)
            context.assets.open(name).use { input ->
                FileOutputStream(file).use { output ->
                    var n = input.read(buf)
                    while (n != -1) { output.write(buf, 0, n); n = input.read(buf) }
                }
            }
            extractNs = System.nanoTime() - extractStart
        }

        val createStart = System.nanoTime()
        // Options are built here, on this thread: a SessionOptions instance is not shared between
        // sessions, and ORT reads it during createSession.
        val session = env.createSession(file.absolutePath, tune.toOptions())
        return SessionLoad(
            label = label,
            session = session,
            verifyNs = verifyNs,
            extractNs = extractNs,
            createNs = System.nanoTime() - createStart,
            bytes = file.length(),
        )
    }

    /**
     * One graph's load result and its timings.
     *
     * Timings are captured with `System.nanoTime` on the worker rather than `Metrics.stage`, because
     * a `Metrics` run is thread-confined by design (R6.3) — stage marks from a worker would find no
     * active run. [report] replays them as counters on the constructing thread, where the run lives,
     * so a startup line still carries per-graph numbers.
     */
    private class SessionLoad(
        val label: String,
        val session: OrtSession,
        val verifyNs: Long,
        val extractNs: Long,
        val createNs: Long,
        val bytes: Long,
    ) {
        fun report() {
            Metrics.counter("verify_us:$label", verifyNs / 1000)
            if (extractNs > 0) Metrics.counter("extract_us:$label", extractNs / 1000)
            Metrics.counter("create_us:$label", createNs / 1000)
            Metrics.counter("bytes:$label", bytes)
        }
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
