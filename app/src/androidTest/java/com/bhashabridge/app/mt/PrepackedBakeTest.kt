package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.bench.Stats
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Q19: §3.44 left **~790 ms of MLAS prepacking** as the largest remaining piece of session load, and
 * §3.30 proved `decoder_init` and `decoder_step` hold byte-identical tensors — so half of that work is
 * the same weights packed twice. ORT shares prepacked buffers between sessions through
 * `PrepackedWeightsContainer`, which the Java API does not expose. What Java *can* reach through
 * `addConfigEntry` is the other route: **write the prepacked weights at bake time** and let every
 * later load read them back instead of computing them.
 *
 * That route does not fit the shipping `.ort` bake, and the native library says so in its own strings:
 * `WritePrepackedToFileAndAddToProto` writes into a `TensorProto`, i.e. the **ONNX** external-data
 * format, and `SavePrePackedConstantInitializers is set to true but the model is not being saved.
 * Ignoring the flag.` is what a mismatched configuration gets. So the arm has to be baked as optimized
 * ONNX with external initializers — which is queue item **Q17**'s shape as well.
 *
 * Three artifacts are therefore baked from the same sources and compared as loads:
 *
 *  - `ort` — ALL_OPT saved in ORT format. **What ships today.**
 *  - `ext` — ALL_OPT saved as ONNX with external initializers. Q17's arm, and the control that
 *    separates "external initializers" from "prepacked": without it, any win here could be either.
 *  - `extpp` — `ext` plus `session.save_external_prepacked_constant_initializers`. Q19 proper.
 *
 * Loads are timed the way production loads: three graphs, three threads, sessions closed immediately.
 * The `ort` arm uses the mapped-initializer path that shipped in §3.44, because that is the baseline
 * this has to beat, not the one it replaced. Arms are rotated per round (§3.27's page-cache lesson).
 *
 * File sizes are logged next to the times: a prepacked layout is bigger on disk, and `filesDir`
 * already holds 584 MB.
 *
 * Logged under `BB.Q19`. This is a probe — it times construction and measures bytes, and proves
 * nothing about parity. If an arm wins, it gets wired into `OnnxModels` and answers to
 * `BenchmarkSuiteTest` like everything else. None did (§3.45).
 *
 * **It leaves ~1.7 GB of baked artifacts in `filesDir/q19`** and reuses them on a re-run rather than
 * paying ~47 s of bakes again. Reclaim it when finished:
 * `adb shell run-as com.bhashabridge.app rm -rf files/q19`.
 */
@RunWith(AndroidJUnit4::class)
class PrepackedBakeTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private val work get() = File(app.filesDir, "q19")
    private val sources = listOf("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")

    @Test
    fun prepackedBakeVersusOrtFormat() {
        val src = File(work, "src").apply { mkdirs() }
        // The graphs carry structure only since Phase 13 — every weight lives in the shared blob, and
        // ONNX resolves an initializer's location relative to the model file, so the blob has to sit
        // in the same directory as the sources or the bake fails while parsing.
        (sources + "weights.bin").forEach { extract(it, File(src, it)) }

        val variants = listOf(
            Variant("ort", File(work, "ort")) { out, name ->
                bakeOptions().apply {
                    addConfigEntry("session.save_model_format", "ORT")
                    setOptimizedModelFilePath(File(out, name.replace(".onnx", ".ort")).absolutePath)
                }
            },
            // The question Q19 actually asks: can the format that *ships* carry prepacked weights?
            // ORT's own string — "SavePrePackedConstantInitializers is set to true but the model is
            // not being saved. Ignoring the flag." — suggests not, but a silently ignored flag and a
            // supported one are told apart by the bytes on disk, not by reading strings.
            Variant("ortpp", File(work, "ortpp")) { out, name ->
                bakeOptions().apply {
                    addConfigEntry("session.save_model_format", "ORT")
                    addConfigEntry("session.save_external_prepacked_constant_initializers", "1")
                    setOptimizedModelFilePath(File(out, name.replace(".onnx", ".ort")).absolutePath)
                }
            },
            Variant("ext", File(work, "ext")) { out, name ->
                bakeOptions().apply {
                    setOptimizedModelFilePath(File(out, name).absolutePath)
                    addConfigEntry("session.optimized_model_external_initializers_file_name", "$name.bin")
                    addConfigEntry("session.optimized_model_external_initializers_min_size_in_bytes", "1024")
                }
            },
            Variant("extpp", File(work, "extpp")) { out, name ->
                bakeOptions().apply {
                    setOptimizedModelFilePath(File(out, name).absolutePath)
                    addConfigEntry("session.optimized_model_external_initializers_file_name", "$name.bin")
                    addConfigEntry("session.optimized_model_external_initializers_min_size_in_bytes", "1024")
                    addConfigEntry("session.save_external_prepacked_constant_initializers", "1")
                }
            },
        )

        for (v in variants) {
            v.dir.mkdirs()
            val artifacts = v.artifacts()
            if (artifacts.all { it.exists() }) {
                Log.i(TAG, "BAKE ${v.label} skipped, artifacts present")
            } else {
                val ms = measure {
                    sources.forEach { name ->
                        env.createSession(File(src, name).absolutePath, v.options(v.dir, name)).close()
                    }
                }
                Log.i(TAG, "BAKE ${v.label} ms=$ms")
            }
            val listing = v.dir.listFiles().orEmpty().sortedBy { it.name }
                .joinToString(" ") { "${it.name}=${it.length()}" }
            Log.i(TAG, "SIZE ${v.label} total=${v.dir.totalBytes()} $listing")
        }

        val results = LinkedHashMap<String, MutableList<Long>>()
        repeat(ROUNDS) { round ->
            val order = variants.drop(round % variants.size) + variants.take(round % variants.size)
            for (v in order) {
                val ms = loadParallel(v)
                results.getOrPut(v.label) { mutableListOf() } += ms
                Log.i(TAG, "ROUND ${round + 1} ${v.label} load_ms=$ms")
            }
        }
        results.forEach { (label, samples) ->
            Log.i(TAG, "ARM $label samples=$samples stats=${Stats.of(samples).toJson()}")
        }
    }

    /** ALL_OPT with production's other knobs — the same shape `OnnxModels.bakeOptions` uses. */
    private fun bakeOptions(): SessionOptions =
        ExecutionPolicy.current.toOptions().apply { setOptimizationLevel(OptLevel.ALL_OPT) }

    /**
     * Loads a variant's three graphs on three threads and returns the wall clock.
     *
     * The ORT-format arm goes through the mapped `ByteBuffer` overload — that is what ships after
     * §3.44 and therefore the number to beat. The ONNX arms are path loads at NO_OPT: the graph on
     * disk is already optimized, and ORT resolves (and maps) their external initializer file itself.
     */
    private fun loadParallel(v: Variant): Long {
        val pool = Executors.newFixedThreadPool(3) { r -> Thread(r, "q19-load").apply { isDaemon = true } }
        val held = java.util.Collections.synchronizedList(ArrayList<ByteBuffer>(3))
        try {
            val start = System.nanoTime()
            val sessions = v.artifacts().map { file ->
                pool.submit(Callable { if (v.label.startsWith("ort")) mappedSession(file, held) else pathSession(file) })
            }.map { it.get() }
            val ms = (System.nanoTime() - start) / 1_000_000
            sessions.forEach { it.close() }
            held.clear()
            return ms
        } finally {
            pool.shutdown()
            System.gc()
            Thread.sleep(SETTLE_MS)
        }
    }

    private fun pathSession(file: File): OrtSession =
        env.createSession(file.absolutePath, warmOptions())

    private fun mappedSession(file: File, held: MutableList<ByteBuffer>): OrtSession {
        val buffer = RandomAccessFile(file, "r").use { raf ->
            raf.channel.use { it.map(FileChannel.MapMode.READ_ONLY, 0, it.size()) }
        }
        held += buffer
        return env.createSession(
            buffer,
            warmOptions().apply {
                addConfigEntry("session.use_ort_model_bytes_directly", "1")
                addConfigEntry("session.use_ort_model_bytes_for_initializers", "1")
            },
        )
    }

    private fun warmOptions(): SessionOptions =
        ExecutionPolicy.current.toOptions().apply { setOptimizationLevel(OptLevel.NO_OPT) }

    private fun extract(name: String, dest: File) {
        if (dest.exists()) return
        app.assets.open(name).use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } }
    }

    private fun File.totalBytes(): Long = listFiles().orEmpty().sumOf { it.length() }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private class Variant(
        val label: String,
        val dir: File,
        val options: (File, String) -> SessionOptions,
    ) {
        /** The three baked graphs, in the order production submits them. */
        fun artifacts(): List<File> = listOf(
            "encoder_int8", "decoder_init_int8", "decoder_step_int8",
        ).map { File(dir, if (label.startsWith("ort")) "$it.ort" else "$it.onnx") }
    }

    private companion object {
        const val TAG = "BB.Q19"
        const val ROUNDS = 3
        const val SETTLE_MS = 1_500L
    }
}
