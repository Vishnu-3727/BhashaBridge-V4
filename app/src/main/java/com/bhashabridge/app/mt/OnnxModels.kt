package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.content.Context
import com.bhashabridge.app.BuildConfig
import com.bhashabridge.app.Direction
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.bench.Metrics
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logWarn
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
        val src = File(context.filesDir, name)
        val verifyStart = System.nanoTime()
        val present = src.exists()
        val verifyNs = System.nanoTime() - verifyStart

        var extractNs = 0L
        if (!present) {
            val extractStart = System.nanoTime()
            extractAsset(context, name, src)
            extractNs = System.nanoTime() - extractStart
        }

        // The tuning sweep (MtTuningSweepTest) varies optLevel per config and must run graph
        // optimization on every load to measure it, so only the production policy opts into the
        // on-disk optimized-graph cache. Everyone else keeps the pre-Phase-2A behaviour verbatim:
        // options built here on this thread (a SessionOptions is not shared between sessions), ORT
        // reads them during createSession.
        if (!tune.optCache) {
            val start = System.nanoTime()
            val session = env.createSession(src.absolutePath, tune.toOptions())
            return SessionLoad(label, session, verifyNs, extractNs, System.nanoTime() - start, false, src.length())
        }
        return loadCached(src, label, verifyNs, extractNs)
    }

    /**
     * Production load. Reuses a previously baked, fully-optimized graph when the cache is valid, so
     * ORT's graph optimization runs once per install instead of on every launch. On a miss the
     * ALL-optimized graph is written to disk as a *side effect* of the session that already serves
     * this launch (`setOptimizedModelFilePath`), so baking costs no extra session. A corrupt or
     * unreadable cache is deleted and regenerated; any failure degrades to the plain source load,
     * so the cache can never break startup.
     */
    private fun loadCached(src: File, label: String, verifyNs: Long, extractNs: Long): SessionLoad {
        val opt = File(src.parentFile, optName(src.name))
        val stamp = File(src.parentFile, stampName(src.name))
        val expected = cacheStamp(src)

        if (opt.exists() && stamp.readTextOrNull() == expected) {
            val start = System.nanoTime()
            try {
                val session = env.createSession(opt.absolutePath, loadOptions())
                val ms = (System.nanoTime() - start) / 1_000_000
                logDebug(LogTag.MT) { "opt-cache HIT $label: loaded ${opt.name} in $ms ms, graph optimization skipped" }
                return SessionLoad(label, session, verifyNs, extractNs, System.nanoTime() - start, false, opt.length())
            } catch (e: OrtException) {
                logWarn(LogTag.MT, "opt-cache load failed for $label; regenerating", e)
                opt.delete(); stamp.delete()
            }
        }

        logDebug(LogTag.MT) { "opt-cache MISS $label: regenerating optimized model because ${bakeReason(opt, stamp)}" }
        return bake(src, opt, stamp, expected, label, verifyNs, extractNs)
    }

    /**
     * Bakes [src] into an ALL-optimized [opt] and returns the session that produced it. If the bake
     * cannot run (e.g. no disk space to write [opt]), falls back to the plain source load — exactly
     * the pre-Phase-2A behaviour — so functionality is preserved even when caching is impossible.
     */
    private fun bake(
        src: File, opt: File, stamp: File, expected: String, label: String, verifyNs: Long, extractNs: Long,
    ): SessionLoad {
        val start = System.nanoTime()
        val session = try {
            env.createSession(src.absolutePath, bakeOptions(opt.absolutePath))
        } catch (e: OrtException) {
            logWarn(LogTag.MT, "opt-cache generation failed for $label; loading source uncached", e)
            runCatching { opt.delete(); stamp.delete() }
            val fbStart = System.nanoTime()
            val fallback = env.createSession(src.absolutePath, tune.toOptions())
            return SessionLoad(label, fallback, verifyNs, extractNs, System.nanoTime() - fbStart, false, src.length())
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        // Stamp written only after a successful bake, so a valid stamp always implies a valid model.
        // A stamp-write failure is non-fatal: the session is good, next launch just regenerates.
        runCatching { stamp.writeText(expected) }
            .onFailure { logWarn(LogTag.MT, "opt-cache stamp write failed for $label; will regenerate next launch", it) }
        logDebug(LogTag.MT) { "opt-cache GENERATED $label in $ms ms -> ${opt.name}" }
        return SessionLoad(label, session, verifyNs, extractNs, System.nanoTime() - start, true, opt.length())
    }

    /** Copies an asset to app-private storage in 1 MB chunks. ORT loads from a file path. */
    private fun extractAsset(context: Context, name: String, dest: File) {
        val buf = ByteArray(1 shl 20)
        context.assets.open(name).use { input ->
            FileOutputStream(dest).use { output ->
                var n = input.read(buf)
                while (n != -1) { output.write(buf, 0, n); n = input.read(buf) }
            }
        }
    }

    /**
     * The cache key. Any change regenerates the optimized model: the app version (graph export or
     * decode changes ship with it), the ONNX Runtime version (optimizer output is version-specific),
     * and the source model's byte length (a re-exported graph is different bytes). Length rather than
     * a content hash — the source is hundreds of MB and hashing it every launch would cost more than
     * the optimization this cache removes; a new export always changes the length.
     */
    private fun cacheStamp(src: File): String = "${BuildConfig.VERSION_CODE}|${env.version}|${src.length()}"

    /** ALL_OPT + write the result to [optPath]. Shares every other knob with [loadOptions] via [tune]. */
    private fun bakeOptions(optPath: String): SessionOptions =
        tune.toOptions().apply {
            setOptimizationLevel(OptLevel.ALL_OPT)
            setOptimizedModelFilePath(optPath)
        }

    /** NO_OPT: the model on disk is already optimized, so skip the optimizers entirely. */
    private fun loadOptions(): SessionOptions =
        tune.toOptions().apply { setOptimizationLevel(OptLevel.NO_OPT) }

    private fun bakeReason(opt: File, stamp: File): String = when {
        !opt.exists() -> "no cached model exists"
        !stamp.exists() -> "cache stamp missing"
        else -> "app / ONNX Runtime / model version changed"
    }

    private fun optName(name: String) = name.removeSuffix(".onnx") + ".opt.onnx"
    private fun stampName(name: String) = name.removeSuffix(".onnx") + ".opt.stamp"
    private fun File.readTextOrNull(): String? = runCatching { if (exists()) readText() else null }.getOrNull()

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
        val baked: Boolean,
        val bytes: Long,
    ) {
        fun report() {
            Metrics.counter("verify_us:$label", verifyNs / 1000)
            if (extractNs > 0) Metrics.counter("extract_us:$label", extractNs / 1000)
            // create_us is the NO_OPT load on a warm launch and the full bake on a cold one; `baked`
            // (1/0) disambiguates which, so a startup line separates first-launch from warm-launch cost.
            Metrics.counter("create_us:$label", createNs / 1000)
            Metrics.counter("baked:$label", if (baked) 1L else 0L)
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
    /**
     * Opt into the Phase 2A on-disk optimized-graph cache: bake ALL_OPT once, then load NO_OPT. Only
     * the production policy sets this. It is deliberately off for the tuning sweep, whose whole job is
     * to measure [optLevel] on every load — a cache would collapse every config onto one baked graph.
     * A routing flag for [OnnxModels], not an ORT knob, so [toOptions] ignores it.
     */
    val optCache: Boolean = false,
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
        fun production() = OrtTuning(name = "production", intraThreads = 2, cpuArena = false, optCache = true)
    }
}
