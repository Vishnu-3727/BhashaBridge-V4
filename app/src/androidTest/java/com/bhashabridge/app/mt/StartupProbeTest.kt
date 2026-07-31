package com.bhashabridge.app.mt

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Phase 11A probe. The startup instrumentation says *where* the 25 s goes; this says *why*.
 *
 * Read-only: it measures alternatives side by side without changing a line of the production load
 * path. Nothing here is a fix — Phase 11A explicitly does not optimise. Each measurement exists to
 * turn a hypothesis in `docs/ENGINE_STARTUP_ANALYSIS.md` into a number.
 */
@RunWith(AndroidJUnit4::class)
class StartupProbeTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    /**
     * Hypothesis: the tokenizer is slow because `parseFlatIntDict` reads through an unbuffered
     * `InputStreamReader` one `read()` per character. Same parser, same file, two readers.
     */
    @Test
    fun probeTokenizerLoad() {
        val name = "dict.TGT.json"
        // NOTE: assets.openFd() throws here — the dictionaries are DEFLATE-compressed in the APK
        // (androidResources.noCompress covers only onnx/bin/pb), so every read inflates on the fly.
        // Size is therefore counted while reading rather than queried.
        var size = 0L
        val rawMs = measure {
            app.assets.open(name).use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    size += n
                }
            }
        }

        var unbuffered: Map<String, Int> = emptyMap()
        val unbufferedMs = measure {
            unbuffered = app.assets.open(name)
                .use { Tokenizer.parseFlatIntDict(InputStreamReader(it, Charsets.UTF_8)) }
        }

        var buffered: Map<String, Int> = emptyMap()
        val bufferedMs = measure {
            buffered = app.assets.open(name).use {
                Tokenizer.parseFlatIntDict(BufferedReader(InputStreamReader(it, Charsets.UTF_8), 1 shl 16))
            }
        }

        Log.i(TAG, "TOKENIZER file=$name bytes=$size raw_read_ms=$rawMs " +
            "unbuffered_parse_ms=$unbufferedMs buffered_parse_ms=$bufferedMs " +
            "entries=${buffered.size} identical=${unbuffered == buffered}")
        assertTrue(unbufferedMs > 0 && bufferedMs > 0)
        // Phase 11B correctness gate: buffering may change when bytes are fetched, never what the
        // parser sees. Map equality covers vocabulary size, every key and every token id.
        assertEquals("vocabulary size changed", unbuffered.size, buffered.size)
        assertEquals("parsed vocabulary differs between readers", unbuffered, buffered)
    }

    /**
     * Hypothesis: session creation is dominated by ONNX Runtime's graph optimisation passes rather
     * than by reading the file. Compares the production options against NO_OPT, and against reading
     * the same bytes off disk.
     *
     * Sessions are closed immediately; this measures construction only.
     */
    @Test
    fun probeSessionCreation() {
        val env = OrtEnvironment.getEnvironment()
        val present = listOf("encoder_int8.onnx", "decoder_init_int8.onnx")
            .map { File(app.filesDir, it) }
            .filter { it.exists() }
        // Phase 2B keeps only the `.ort` cache in filesDir and purges the source `.onnx`, so on the
        // shipping load path this probe has nothing to open. It used to log SKIPPED and pass green,
        // which made a probe that measured nothing indistinguishable from one that measured fine.
        // An assumption reports it as skipped in the run results instead. See ARCHITECTURE_RULES.
        assumeTrue(
            "no source .onnx in filesDir — the production cache purges it; run a build with " +
                "OrtTuning.optCache=false to exercise this probe",
            present.isNotEmpty(),
        )
        for (path in present) {
            val model = path.name
            val diskMs = measure {
                path.inputStream().use { input ->
                    val buf = ByteArray(1 shl 20)
                    while (input.read(buf) != -1) { /* I/O floor for the same file */ }
                }
            }
            val productionMs = measure {
                env.createSession(path.absolutePath, ExecutionPolicy.current.toOptions()).close()
            }
            val noOptMs = measure {
                val tune = ExecutionPolicy.current.copy(
                    optLevel = OrtSession.SessionOptions.OptLevel.NO_OPT,
                )
                env.createSession(path.absolutePath, tune.toOptions()).close()
            }
            val singleThreadMs = measure {
                val tune = ExecutionPolicy.current.copy(intraThreads = 1)
                env.createSession(path.absolutePath, tune.toOptions()).close()
            }
            Log.i(TAG, "SESSION $model bytes=${path.length()} disk_read_ms=$diskMs " +
                "production_ms=$productionMs no_opt_ms=$noOptMs intra1_ms=$singleThreadMs")
        }
    }

    /** Hypothesis: the three sessions are independent, so their loads could overlap. Measures the
     *  serial sum against a parallel load of the same three graphs. Measurement only — the runtime
     *  still loads them sequentially. */
    @Test
    fun probeParallelSessionLoad() {
        val env = OrtEnvironment.getEnvironment()
        val models = listOf("encoder_int8.onnx", "decoder_init_int8.onnx", "decoder_step_int8.onnx")
            .map { File(app.filesDir, it) }
            .filter { it.exists() }
        // Same Phase 2B purge as probeSessionCreation: skip visibly rather than pass green.
        assumeTrue(
            "fewer than 3 source .onnx in filesDir — the production cache purges them; run a build " +
                "with OrtTuning.optCache=false to exercise this probe",
            models.size == 3,
        )

        val serialMs = measure {
            models.forEach { env.createSession(it.absolutePath, ExecutionPolicy.current.toOptions()).close() }
        }
        val parallelMs = measure {
            val sessions = models.map { file ->
                Thread {
                    env.createSession(file.absolutePath, ExecutionPolicy.current.toOptions()).close()
                }.apply { start() }
            }
            sessions.forEach { it.join() }
        }
        Log.i(TAG, "PARALLEL serial_ms=$serialMs parallel_ms=$parallelMs")
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private companion object {
        const val TAG = "BB_STARTUP_PROBE"
    }
}
