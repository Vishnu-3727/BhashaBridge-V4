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
import java.io.IOException
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
 *
 * Q21 (§3.47) changed **what the cache is**, not how it works: the bake writes optimized ONNX that
 * still points at the one shared weight blob, instead of an ORT flatbuffer that re-inlined those
 * weights into each graph. Same bake-once-load-NO_OPT design, 267 MB on disk instead of 473 MB. The
 * blob is consequently permanent rather than bake scratch — see [purgeWeightPart].
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

    /**
     * The weight blob this direction's three graphs share (Phase 13, `model_pipeline/dedup_weights.py`).
     *
     * `torch.onnx.export` writes a full copy of the decoder's weights into `decoder_init` *and*
     * `decoder_step`; hashing the raw tensor bytes showed `decoder_step` holds **no unique tensor data
     * at all** — 192.3 MB of it (EN→HI) is byte-identical to tensors already in `decoder_init`. The
     * graphs now carry structure only and point their initializers at one content-addressed file, so
     * the APK ships those bytes once. ONNX resolves an initializer's `location` **relative to the
     * model file**, which is why the blob must land in `filesDir` beside the extracted graphs.
     *
     * Declared above [init] deliberately: Kotlin runs property initializers in declaration order and
     * the three graphs load *inside* `init`, so a blob name declared after it would still be null when
     * the first bake asks for it. Same trap as the Q14 `mappedModels` list.
     */
    private val sharedWeights: String = when (direction) {
        Direction.EN_TO_HI -> "weights.bin"
        Direction.HI_TO_EN -> "hi_en_weights.bin"
    }

    /** Guards the one-time extraction of [sharedWeights]; the three graph loads race for it. */
    private val weightsLock = Any()

    /**
     * Length of [sharedWeights] in the APK, or `0` when this build ships self-contained graphs.
     *
     * Both asset layouts are supported on purpose. A build made before the Phase 13 export — or one
     * rolled back to it — has every weight inside its graphs and no blob, and must keep working: this
     * is the difference between a bad export being a rebuild and being a bricked launch. `0` disables
     * the extraction and drops out of the cache stamp, so the old layout behaves exactly as it did.
     */
    private val sharedWeightsLength: Long =
        runCatching { context.assets.openFd(sharedWeights).use { it.length } }.getOrDefault(0L)

    init {
        // The blob is now **permanent**, not bake scratch (Q21, §3.47). The baked graphs are ~1 MB of
        // structure that reference it by name, resolved relative to the model file, so it has to be on
        // disk beside them for every load — not just while baking. Extracted here, once, before the
        // three loads start: doing it on the loader threads would have all three race for the same
        // 264 MB copy, and [ensureSharedWeights] is an `exists()` check on every launch after the first.
        purgeWeightPart(context.filesDir)
        ensureSharedWeights(context)

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

    /**
     * P8: enable ORT's built-in profiler on [options] with a per-graph file prefix, when [tune] opted
     * in ([OrtTuning.profileDir]). No-op otherwise, so the default path is byte-for-byte unchanged. ORT
     * appends `_<timestamp>.json` to the prefix, so the encoder / decoder_init / decoder_step traces are
     * distinct files and no run overwrites another. Overhead falls entirely on opted-in runs.
     */
    private fun SessionOptions.maybeProfile(label: String): SessionOptions = apply {
        val dir = tune.profileDir ?: return@apply
        File(dir).mkdirs()
        enableProfiling(File(dir, "ort_$label").absolutePath)
    }

    /**
     * P8: flush and close each session's profile file, returning their paths (empty when profiling was
     * not enabled). ORT's `endProfiling` writes the buffered trace and returns the filename; call it
     * once, after the runs to be measured and before [release]. Failures are swallowed per-session so a
     * flush problem on one graph never loses the others or blocks teardown.
     */
    fun endProfiling(): List<String> {
        if (tune.profileDir == null) return emptyList()
        return listOf(encoder, decoderInit, decoderStep).mapNotNull {
            runCatching { it.endProfiling() }.getOrNull()
        }
    }

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

    /** One graph: resolve or build its session. Runs entirely on one worker thread. */
    private fun loadSession(context: Context, name: String, label: String): SessionLoad {
        // Benchmark-only (Q20): load a pre-built graph of the same name from an arbitrary directory,
        // so a probe can drive raw / optimized-inline / optimized-external artifacts through the real
        // engine instead of through hand-built sessions. Nothing in production sets it.
        tune.graphDir?.let { dir -> return loadFrom(File(dir, name), label) }
        // The tuning sweep (MtTuningSweepTest) varies optLevel per config and must run graph
        // optimization on every load to measure it, so only the production policy uses the ORT-format
        // cache. Everyone else keeps the pre-Phase-2A behaviour verbatim: extract the source .onnx to
        // filesDir on demand, then a path-based createSession with this config's options.
        if (!tune.optCache) return loadSourceUncached(context, name, label)
        return loadCached(context, name, label)
    }

    /**
     * Benchmark-only (Q20): a plain path load of [file] with this config's options and no cache, no
     * extraction and no bake. The caller owns the file's format and its optimization level — an
     * already-optimized graph wants `NO_OPT`, a raw one does not.
     */
    private fun loadFrom(file: File, label: String): SessionLoad {
        val start = System.nanoTime()
        val session = env.createSession(file.absolutePath, tune.toOptions().maybeProfile(label))
        return SessionLoad(label, session, 0L, 0L, System.nanoTime() - start, false, file.length())
    }

    /** Non-production path: source `.onnx` extracted to filesDir if absent, then loaded from its path. */
    private fun loadSourceUncached(context: Context, name: String, label: String): SessionLoad {
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
        // The shared blob this graph references is extracted by [init], before any load starts.
        val start = System.nanoTime()
        val session = env.createSession(src.absolutePath, tune.toOptions().maybeProfile(label))
        return SessionLoad(label, session, verifyNs, extractNs, System.nanoTime() - start, false, src.length())
    }

    /**
     * Production load. Reuses a previously baked **optimized ONNX** graph when the cache is valid,
     * loaded NO_OPT from its path, so ORT's optimizers run once per install instead of every launch.
     * On a miss the ALL-optimized graph is written to disk as a *side effect* of the session that
     * already serves this launch (`setOptimizedModelFilePath`).
     *
     * **Why this format and not the `.ort` flatbuffer it replaced (Q21, §3.47).** ORT's optimized-ONNX
     * writer preserves the source graph's external-data references, so the three baked graphs are ~1 MB
     * each and all point at the one shared blob (§3.30). The ORT-format writer instead re-inlines every
     * weight into each graph, which duplicated the decoder weights on disk and cost 473 MB against this
     * layout's 267 MB. §3.46 measured both through the real engine: load, first inference and
     * steady-state latency all tie, and this side is −193 MB of storage and −333 MB of PSS.
     *
     * The source `.onnx` is extracted to filesDir only for that one-time bake and is purged on the next
     * launch. The blob is **not** scratch — it backs the baked graphs for the life of the install.
     *
     * A corrupt cache is deleted and regenerated; any failure degrades to an uncached session built
     * from the source, so the cache can never break startup.
     */
    private fun loadCached(context: Context, name: String, label: String): SessionLoad {
        purgeLegacy(context.filesDir, name) // remove the superseded .ort cache and the source copy
        val opt = File(context.filesDir, optName(name))
        val stamp = File(context.filesDir, stampName(name))

        val verifyStart = System.nanoTime()
        val expected = cacheStamp(context, name)
        val hit = opt.exists() && stamp.readTextOrNull() == expected
        val verifyNs = System.nanoTime() - verifyStart

        if (hit) {
            val start = System.nanoTime()
            try {
                val session = env.createSession(opt.absolutePath, loadOptions().maybeProfile(label))
                val ms = (System.nanoTime() - start) / 1_000_000
                logDebug(LogTag.MT) { "opt-cache HIT $label: loaded ${opt.name} in $ms ms, graph optimization skipped" }
                return SessionLoad(label, session, verifyNs, 0L, System.nanoTime() - start, false, opt.length())
            } catch (e: OrtException) {
                logWarn(LogTag.MT, "opt-cache load failed for $label; regenerating", e)
                opt.delete(); stamp.delete()
            }
        }

        logDebug(LogTag.MT) { "opt-cache invalidated $label: rebuilding because ${bakeReason(opt, stamp)}" }
        return bake(context, name, opt, stamp, expected, label, verifyNs)
    }

    /**
     * Extracts [name]'s source once and builds [opt] (optimized ONNX) from its file path — ORT mmaps
     * the source, so three concurrent bakes stay low-heap. Returns the session that produced it. If the
     * build fails (e.g. no disk space to write [opt]), falls back to an uncached session from the same
     * source, so functionality is preserved even when caching is impossible.
     */
    private fun bake(
        context: Context, name: String, opt: File, stamp: File, expected: String, label: String, verifyNs: Long,
    ): SessionLoad {
        val src = File(context.filesDir, name)
        val readStart = System.nanoTime()
        // The blob is already on disk — [init] extracts it before any load, because both the source
        // graph being read here and the optimized graph being written resolve their initializers
        // against it. A graph without its weights beside it fails the build rather than loading empty.
        if (!src.exists()) extractAsset(context, name, src) // purged on the next launch; see loadCached
        val readNs = System.nanoTime() - readStart

        val start = System.nanoTime()
        val session = try {
            logDebug(LogTag.MT) { "Building optimized model $label from $name" }
            env.createSession(src.absolutePath, bakeOptions(opt.absolutePath).maybeProfile(label))
        } catch (e: OrtException) {
            logWarn(LogTag.MT, "optimized build failed for $label; loading source uncached", e)
            runCatching { opt.delete(); stamp.delete() }
            val fbStart = System.nanoTime()
            val fallback = env.createSession(src.absolutePath, tune.toOptions().maybeProfile(label))
            return SessionLoad(label, fallback, verifyNs, readNs, System.nanoTime() - fbStart, false, src.length())
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        // Stamp written only after a successful build, so a valid stamp always implies a valid graph.
        // A stamp-write failure is non-fatal: the session is good, next launch just regenerates.
        runCatching { stamp.writeText(expected) }
            .onFailure { logWarn(LogTag.MT, "opt-cache stamp write failed for $label; will regenerate next launch", it) }
        logDebug(LogTag.MT) { "optimized model GENERATED $label in $ms ms -> ${opt.name}" }
        return SessionLoad(label, session, verifyNs, readNs, System.nanoTime() - start, true, opt.length())
    }

    /**
     * Copies an asset to app-private storage in 1 MB chunks. ORT loads (and mmaps) from a file path.
     *
     * Written to a `.part` file and renamed on completion, because every reader of these files tests
     * `exists()` alone. A copy interrupted part-way — process killed, storage full, install stopped —
     * would otherwise leave a truncated several-hundred-MB `.onnx` that looks complete forever after,
     * and ORT fails to parse it on every launch from then on.
     */
    private fun extractAsset(context: Context, name: String, dest: File) {
        val part = File(dest.parentFile, "${dest.name}.part")
        // Checked before a byte is written, because the failure this prevents is expensive and
        // opaque: three graphs are extracted concurrently, each a few hundred MB, and running out
        // of space part-way through surfaces as an IOException from a write — which the UI reports
        // with the same "direction unavailable" message it uses for a direction that was never
        // exported. The headroom is the asset itself plus the optimized graph the bake writes from it.
        //
        // Since Q21 (§3.47) the bake emits **optimized ONNX that keeps pointing at [sharedWeights]**,
        // so its output is about the size of the source graph — not the hundreds of megabytes the
        // `.ort` writer produced by re-inlining every weight. `graph * 2` is therefore the right
        // reservation again for a graph, and the blob is reserved on its own extraction, which is
        // what the `name == sharedWeights` case covers. The blob is not double-counted here because
        // [init] extracts it before any graph is touched, so by the time this runs for a graph the
        // blob's bytes are already spent, not pending.
        val graphLength = assetLength(context, name)
        val needed = if (name == sharedWeights) graphLength else graphLength * 2
        val free = dest.parentFile?.usableSpace ?: Long.MAX_VALUE
        if (free < needed) {
            throw IOException(
                "not enough storage to install $name: needs ${needed / (1 shl 20)} MB, " +
                    "${free / (1 shl 20)} MB free"
            )
        }
        val buf = ByteArray(1 shl 20)
        try {
            context.assets.open(name).use { input ->
                FileOutputStream(part).use { output ->
                    var n = input.read(buf)
                    while (n != -1) { output.write(buf, 0, n); n = input.read(buf) }
                }
            }
            dest.delete()
            if (!part.renameTo(dest)) throw IOException("could not publish extracted asset $name")
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    private fun assetLength(context: Context, name: String): Long =
        context.assets.openFd(name).use { it.length }

    /**
     * Extracts [sharedWeights] once, whichever of the three concurrent bakes gets there first.
     *
     * Double-checked around [weightsLock]: the fast path is an `exists()` with no lock at all, which
     * is what the warm-ish case (one graph stale, two cached) hits. The check inside the lock is not
     * redundant — without it the two losers of the race re-extract a few hundred MB over a file the
     * winner just published.
     */
    private fun ensureSharedWeights(context: Context) {
        if (sharedWeightsLength == 0L) return // self-contained graphs; nothing to extract
        val dest = File(context.filesDir, sharedWeights)
        if (dest.exists()) return
        synchronized(weightsLock) {
            if (dest.exists()) return
            logDebug(LogTag.MT) { "Extracting shared weights $sharedWeights" }
            extractAsset(context, sharedWeights, dest)
        }
    }

    /**
     * Removes a `.part` left behind by an extraction that was killed mid-copy. Nothing else knows that
     * name, so without this it is a few hundred MB of dead file until the app is uninstalled.
     *
     * **It does not touch [sharedWeights] itself, and that is the Q21 change.** Before §3.47 the blob
     * was bake scratch and was deleted at the start of every launch; the baked `.ort` had re-inlined
     * the weights, so nothing needed it afterwards. The optimized-ONNX graphs the bake writes now
     * reference it by name for the life of the install — deleting it would break every subsequent
     * load, not free space.
     */
    private fun purgeWeightPart(dir: File) {
        val f = File(dir, "$sharedWeights.part")
        if (f.exists() && f.delete()) logDebug(LogTag.MT) { "removed obsolete ${f.name}" }
    }

    /**
     * The cache key. Any change regenerates the baked model: the cache **format** (see below), the app
     * version (graph export or decode changes ship with it), the ONNX Runtime version (optimizer output
     * is version-specific), and the source asset's byte length (a re-exported graph is different bytes).
     * Length via a cheap `openFd`, not a content hash — the source is hundreds of MB and hashing it
     * every launch would cost more than the optimization this cache removes; a new export always
     * changes the length.
     *
     * **[sharedWeights]'s length is part of the key, and has to be.** Since Phase 13 the graph file
     * holds structure only — about 2 MB — and every weight lives in the blob. Keying on the graph
     * alone would let a re-quantized export (per-channel scales, a different calibration) ship
     * hundreds of MB of new weights under a graph whose length barely moved, and the app would
     * happily reuse the stale graph built from the old ones. Wrong translations, no error, and a
     * cache that only clears on reinstall.
     *
     * **[CACHE_FORMAT] leads the key** so the Q21 switch invalidates itself. The previous cache used
     * the same `.opt.onnx` / `.opt.stamp` names as the abandoned Phase 2A one, and a stale artifact
     * that merely *looks* current is the failure mode this whole key exists to prevent. Bumping the
     * token is also how any future format change forces a rebake.
     */
    private fun cacheStamp(context: Context, name: String): String =
        "$CACHE_FORMAT|${BuildConfig.VERSION_CODE}|${env.version}|${assetLength(context, name)}" +
            if (sharedWeightsLength == 0L) "" else "|$sharedWeightsLength"

    /**
     * ALL_OPT + serialize the result to [optPath] as optimized ONNX. Shares every other knob via [tune].
     *
     * No `session.save_model_format` and no external-initializer keys, and both omissions are the point
     * (§3.47): ORT's ONNX writer **preserves the source's external-data references**, so the output is
     * ~1 MB of structure still pointing at [sharedWeights]. Asking for ORT format re-inlines every
     * weight per graph (473 MB), and asking for its own external file writes one blob per graph
     * (471 MB); leaving both alone keeps the single shared blob (§3.30) and lands at 267 MB.
     */
    private fun bakeOptions(optPath: String): SessionOptions =
        tune.toOptions().apply {
            setOptimizationLevel(OptLevel.ALL_OPT)
            setOptimizedModelFilePath(optPath)
        }

    /** NO_OPT: the graph on disk is already optimized, so skip the optimizers on every later launch. */
    private fun loadOptions(): SessionOptions =
        tune.toOptions().apply { setOptimizationLevel(OptLevel.NO_OPT) }

    private fun bakeReason(opt: File, stamp: File): String = when {
        !opt.exists() -> "no optimized model exists"
        !stamp.exists() -> "cache stamp missing"
        else -> "cache format / app / ONNX Runtime / model version changed"
    }

    /**
     * Deletes the superseded caches and the filesDir source copy for [name]; each is now obsolete.
     *
     * The `.ort` pair is on the list because Q21 (§3.47) replaced that format, and an install upgrading
     * across the change would otherwise keep **473 MB** of flatbuffer that nothing will ever open again
     * — the storage this change exists to save, silently not saved.
     *
     * `$name.part` is here because a process killed mid-[extractAsset] leaves one behind and nothing
     * else knows the name.
     */
    private fun purgeLegacy(dir: File, name: String) {
        val base = name.removeSuffix(".onnx")
        listOf(name, "$name.part", "$base.ort", "$base.ort.stamp").forEach { legacy ->
            val f = File(dir, legacy)
            if (f.exists() && f.delete()) logDebug(LogTag.MT) { "cache invalidated: removed obsolete ${f.name}" }
        }
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

        /**
         * Cache-format token, first field of [cacheStamp]. `onnx1` is Q21's optimized-ONNX layout
         * (§3.47). Bump it whenever the baked artifact changes shape, so existing installs rebake
         * instead of loading something built by different rules.
         */
        const val CACHE_FORMAT = "onnx1"
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
    /**
     * Phase 3: `session.intra_op_thread_affinities` (see [ExecutionPolicy.affinityString]). Pins ORT's
     * intra-op worker threads to the performance cluster. ORT's contract: the number of `;`-separated
     * groups must equal `intraThreads - 1`, because ORT never sets affinity on the calling (main)
     * thread — so this is only meaningful with [intraThreads] set and > 1. `null`/blank = leave
     * scheduling to the OS (affinity OFF), the pre-Phase-3 behaviour.
     */
    val intraOpAffinities: String? = null,
    /**
     * Sets MLAS's `mlas.disable_kleidiai`, turning off Arm's KleidiAI microkernels. **Benchmark-only**
     * — the default of false is what ships, because KleidiAI is a straight win where it dispatches.
     * Exists so the SME/i8mm contribution can be isolated by A/B, which neither ORT profiling nor the
     * CPU feature flags can do on their own.
     */
    val disableKleidiAi: Boolean = false,
    /**
     * Sets `session.disable_prepacking`. **Benchmark-only**, like [disableKleidiAi] — prepacking is a
     * straight win for inference and nothing ships with it off.
     *
     * It existed to answer Q13 — whether MLAS pre-packing was what dropped the model's file mapping
     * — and the answer is **no**. Measured on the SM-M315F (cooled, `MmapPrepackTest`):
     *
     * | | prepack on (ships) | prepack off |
     * |---|---|---|
     * | mapped during load | 451 MB | 451 MB |
     * | mapped after load | 0 | 0 |
     * | native heap allocated | 559 MB | **1,067 MB** |
     * | translate median | 636 ms | **2,909 ms** |
     *
     * The mapping disappears either way, so prepacking is not the mechanism. And prepacking is not
     * a memory/speed trade at all — it is **4.6× faster and uses half the heap**. Keep it on; this
     * flag is a measurement instrument and a REVERT, never a tuning option.
     */
    val disablePrepacking: Boolean = false,
    /**
     * P8 operator profiling. When non-null, ONNX Runtime's built-in profiler is enabled on every
     * session, writing one Chrome-trace JSON per graph under this directory (see
     * [OnnxModels.endProfiling]). `null` (default) = profiler off = **zero runtime overhead and no
     * behaviour change** — this is the "debug flag": profiling exists only when a caller opts in with a
     * directory. Like [optCache], it is a routing flag for [OnnxModels], not an ORT SessionOptions knob
     * (the profile file prefix must be per-graph, which [toOptions] cannot know), so [toOptions] ignores
     * it. Analyse the output with model_pipeline/ort_profile_report.py.
     */
    val profileDir: String? = null,
    /**
     * **Benchmark-only** (Q20, §3.46). Load each graph from `<graphDir>/<asset name>` instead of from
     * the asset pipeline: no extraction, no `.ort` cache, no bake — just a path load with these
     * options. It exists so the graph-format matrix (raw ONNX / optimized ONNX / optimized ONNX with
     * external initializers / prepacked) can be measured through the **real engine**, with real
     * translations, rather than through hand-built sessions that cannot decode.
     *
     * The caller owns the pairing of format and [optLevel]: an already-optimized graph must be loaded
     * `NO_OPT` or ORT will optimize it a second time. `null` (default) leaves the production routing
     * untouched, which is why this is a field and not a branch in [ExecutionPolicy].
     */
    val graphDir: String? = null,
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
        // Affinity requires intra_op_num_threads set; guarded so the group count always matches.
        if (intraThreads != null && !intraOpAffinities.isNullOrBlank()) {
            o.addConfigEntry("session.intra_op_thread_affinities", intraOpAffinities)
        }
        // Measurement knob only — never set in production. MLAS dispatches KleidiAI microkernels on
        // capable silicon; on the SM-S948B (SME) the hot int8 GEMM is KleidiAI's `smopa` SME kernel,
        // confirmed by simpleperf. Turning it off is the only way to attribute the gain, since the
        // dispatch is otherwise invisible to ORT profiling. See bench/results/cross-device/.
        if (disableKleidiAi) o.addConfigEntry("mlas.disable_kleidiai", "1")
        // Measurement knob only — see the field's KDoc. Never set in production.
        if (disablePrepacking) o.addConfigEntry("session.disable_prepacking", "1")
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
