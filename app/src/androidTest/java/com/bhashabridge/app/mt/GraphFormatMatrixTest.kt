package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Metrics
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.bench.SystemStats
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Q20: is the **artifact itself** — not the options used to load it — worth changing?
 *
 * §3.44 and §3.45 measured load paths over one artifact (the baked `.ort`). This measures the
 * artifacts: raw ONNX, optimized ONNX, optimized ONNX with external initializers, the same with
 * prepacked weights, and the ORT flatbuffer by both of its load paths. Each one is driven through the
 * **real engine** — `MtEngine`, real tokenizer, real translations — because a format that loads fast
 * and then decodes slowly is not an improvement, and hand-built sessions cannot decode.
 *
 * Four numbers per arm, which is the whole point of doing it this way:
 *
 *  - **startup** — engine construction (tokenizer parse included, identical across arms, so the
 *    delta is still the graphs);
 *  - **memory** — native heap, PSS, and how much of the model is still *mapped* after the load;
 *  - **first inference** — the run that pays for anything the load deferred (lazy paging, first-touch
 *    of prepacked buffers);
 *  - **steady-state inference** — n=12 after warm-up, which is what a user actually feels.
 *
 * The arms that are not `ort_*` reach the engine through [OrtTuning.graphDir], a benchmark-only
 * routing flag. Every arm is otherwise the production policy, and each is checked for output parity —
 * a format that changes the translation is not a candidate at any speed.
 *
 * Arms are rotated per round (§3.27). Artifacts are baked once into `filesDir/q20` and reused;
 * that is ~2 GB, so reclaim it when finished:
 * `adb shell run-as com.bhashabridge.app rm -rf files/q20`.
 *
 * Logged under `BB.Q20`.
 */
@RunWith(AndroidJUnit4::class)
class GraphFormatMatrixTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val work get() = File(app.filesDir, "q20")
    private val sources = listOf("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")

    @Test
    fun graphFormatMatrix() {
        val src = File(work, "src").apply { mkdirs() }
        // Phase 13 graphs carry structure only; ONNX resolves external initializers relative to the
        // model file, so the shared blob has to sit beside them or nothing loads.
        (sources + BLOB).forEach { name ->
            val dest = File(src, name)
            if (!dest.exists()) app.assets.open(name).use { i -> dest.outputStream().use { i.copyTo(it, 1 shl 20) } }
        }

        // ORT's optimized-ONNX writer **keeps** the source's external-data references unless asked to
        // write its own, so this arm is 3.4 MB of graphs pointing back at the shared Phase 13 blob —
        // which has to be copied in beside them, and which makes this the only arm where the two
        // decoders share one copy of their identical weights. That is exactly Q17's shape.
        bake("opt", src, needsBlob = true) { out, name -> setOptimizedModelFilePath(File(out, name).absolutePath) }
        bake("ext", src) { out, name ->
            setOptimizedModelFilePath(File(out, name).absolutePath)
            addConfigEntry("session.optimized_model_external_initializers_file_name", "$name.bin")
            addConfigEntry("session.optimized_model_external_initializers_min_size_in_bytes", "1024")
        }
        bake("extpp", src) { out, name ->
            setOptimizedModelFilePath(File(out, name).absolutePath)
            addConfigEntry("session.optimized_model_external_initializers_file_name", "$name.bin")
            addConfigEntry("session.optimized_model_external_initializers_min_size_in_bytes", "1024")
            addConfigEntry("session.save_external_prepacked_constant_initializers", "1")
        }

        val production = ExecutionPolicy.current
        val arms = listOf(
            // The pre-Phase-2A behaviour: the source graph, optimized by ORT on every launch.
            Arm("raw_allopt", src, production.copy(graphDir = src.path, optLevel = OptLevel.ALL_OPT)),
            // The same bytes with the optimizers switched off — what graph optimization is worth,
            // priced on this model rather than assumed.
            Arm("raw_noopt", src, production.copy(graphDir = src.path, optLevel = OptLevel.NO_OPT)),
            Arm("opt_inline", File(work, "opt"), production.copy(graphDir = File(work, "opt").path, optLevel = OptLevel.NO_OPT)),
            Arm("opt_ext", File(work, "ext"), production.copy(graphDir = File(work, "ext").path, optLevel = OptLevel.NO_OPT)),
            Arm("opt_ext_pp", File(work, "extpp"), production.copy(graphDir = File(work, "extpp").path, optLevel = OptLevel.NO_OPT)),
            // Whatever the production policy currently bakes and loads from filesDir. Q21 (§3.47)
            // made that `opt_inline`'s layout, so this arm and that one now measure the same shape by
            // different routes — the `ort_path` / `ort_mapped` arms this list used to carry are gone
            // with the format they loaded. Their numbers are in §3.46 and are not re-derivable here.
            Arm("shipping", app.filesDir, production),
        )
        arms.forEach { Log.i(TAG, "SIZE ${it.label} bytes=${it.artifactBytes()}") }

        val load = LinkedHashMap<String, MutableList<Long>>()
        val first = LinkedHashMap<String, MutableList<Long>>()
        val steady = LinkedHashMap<String, MutableList<Double>>()
        repeat(ROUNDS) { round ->
            val order = arms.drop(round % arms.size) + arms.take(round % arms.size)
            for (arm in order) {
                val r = run(arm)
                load.getOrPut(arm.label) { mutableListOf() } += r.loadMs
                first.getOrPut(arm.label) { mutableListOf() } += r.firstMs
                steady.getOrPut(arm.label) { mutableListOf() } += r.steadyMedian
            }
        }
        arms.forEach { arm ->
            Log.i(
                TAG,
                "ARM ${arm.label} load=${load[arm.label]} first=${first[arm.label]} steady=${steady[arm.label]}",
            )
        }
    }

    /** Bakes the three graphs into `work/<label>` with ALL_OPT, unless they are already there. */
    private fun bake(
        label: String,
        src: File,
        needsBlob: Boolean = false,
        options: SessionOptions.(File, String) -> Unit,
    ) {
        val out = File(work, label).apply { mkdirs() }
        if (needsBlob) File(src, BLOB).copyTo(File(out, BLOB), overwrite = true)
        if (sources.all { File(out, it).exists() }) {
            Log.i(TAG, "BAKE $label skipped, artifacts present")
            return
        }
        val start = System.nanoTime()
        sources.forEach { name ->
            val opts = ExecutionPolicy.current.toOptions().apply {
                setOptimizationLevel(OptLevel.ALL_OPT)
                options(out, name)
            }
            env.createSession(File(src, name).absolutePath, opts).close()
        }
        Log.i(TAG, "BAKE $label ms=${(System.nanoTime() - start) / 1_000_000}")
    }

    private fun run(arm: Arm): Result {
        // Metrics is thread-confined, and construction happens here, so the engine's own stage marks
        // (sessions:parallel, create_us per graph) land in the run this opens.
        Metrics.begin("q20_${arm.label}")
        val loadStart = System.nanoTime()
        val engine = MtEngine(app, Direction.EN_TO_HI, tune = arm.tune)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        Metrics.end()

        val firstStart = System.nanoTime()
        val output = engine.translate(SENTENCE)
        val firstMs = (System.nanoTime() - firstStart) / 1_000_000

        repeat(2) { engine.translate(SENTENCE) }
        val latencies = (0 until STEADY_N).map {
            val t = System.nanoTime()
            engine.translate(SENTENCE)
            (System.nanoTime() - t) / 1_000_000
        }
        val timing = Stats.of(latencies)
        val mapped = mappedModelBytes()
        val stats = SystemStats.capture(app, arm.label)

        Log.i(
            TAG,
            "REPORT ${arm.label} load_ms=$loadMs first_ms=$firstMs" +
                " steady_median=${timing.median} steady_p95=${timing.p95} steady_stdev=${timing.stdev}" +
                " mapped_kb=${mapped.first / 1024} mappings=${mapped.second}" +
                " heap_alloc_kb=${Debug.getNativeHeapAllocatedSize() / 1024}" +
                " pss_kb=${stats.totalPssKb} native_pss_kb=${stats.nativePssKb}" +
                " temp_c=${stats.batteryTempC}",
        )
        // Parity is the gate, not a footnote: a format that changes the translation is out.
        Log.i(TAG, "REPORT ${arm.label} output=$output")

        engine.release()
        Thread.sleep(RELEASE_SETTLE_MS) // §3.25: the allocator returns pages ~10 s after release
        return Result(loadMs, firstMs, timing.median)
    }

    private fun mappedModelBytes(): Pair<Long, Int> = runCatching {
        var bytes = 0L
        var count = 0
        File("/proc/self/maps").forEachLine { line ->
            if (line.contains("/com.bhashabridge") && (line.contains(".ort") || line.contains(".onnx") || line.contains(".bin"))) {
                val range = line.substringBefore(' ')
                bytes += range.substringAfter('-').toLong(16) - range.substringBefore('-').toLong(16)
                count++
            }
        }
        bytes to count
    }.getOrDefault(0L to 0)

    private inner class Arm(val label: String, val dir: File, val tune: OrtTuning) {
        /**
         * Every byte this arm needs on disk. The blob and any external initializer files count — a
         * graph that cannot resolve its initializers does not load, so they are part of the artifact,
         * not overhead beside it.
         *
         * The `shipping` arm reads `filesDir`, which also holds the HI→EN trio and the Vosk models, so
         * it is narrowed to this direction's baked graphs plus its blob rather than the whole
         * directory; every other arm owns its directory outright.
         */
        fun artifactBytes(): Long = if (label == "shipping") {
            dir.listFiles().orEmpty()
                .filter { f ->
                    f.name == BLOB || sources.any { f.name.startsWith(it.removeSuffix(".onnx")) && !f.name.endsWith(".stamp") }
                }
                .sumOf { it.length() }
        } else {
            dir.listFiles().orEmpty().sumOf { it.length() }
        }
    }

    private class Result(val loadMs: Long, val firstMs: Long, val steadyMedian: Double)

    private companion object {
        const val BLOB = "weights.bin"
        const val TAG = "BB.Q20"
        const val ROUNDS = 2
        const val STEADY_N = 12
        const val RELEASE_SETTLE_MS = 12_000L
        const val SENTENCE = "The weather is very nice today and I want to go outside."
    }
}
