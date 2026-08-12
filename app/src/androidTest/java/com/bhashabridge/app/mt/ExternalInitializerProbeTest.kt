package com.bhashabridge.app.mt

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel

/**
 * Q24, feasibility: does `SessionOptions.addExternalInitializers` make ONNX Runtime **use** the tensor
 * it is handed, or copy it into the session allocator like everything else?
 *
 * §3.55 measured the problem — `decoder_init` and `decoder_step` hold byte-identical weights and each
 * allocates its own copy, 884 KB of 323,756 KB shared, i.e. nothing. `addExternalInitializers` is the
 * only Java-reachable mechanism that could fix that, and the entire plan depends on one fact nobody
 * has measured: whether a supplied tensor replaces the session's own allocation.
 *
 * One tensor is enough to answer it. `decoder.embed_tokens.weight_quantized` is UINT8 [122672, 512] —
 * **62,808,064 bytes at offset 75,272,192** in `weights.bin` — the largest single initializer in the
 * graph. If supplying it externally drops the session's native-heap growth by ~61 MB, the mechanism
 * works and the full version is worth building. If the growth is unchanged, ORT copied it anyway and
 * the idea is dead for the price of this test.
 *
 * The buffer is a mapping of the real blob, so this also checks the thing the full version would rely
 * on: that ORT accepts a `MappedByteBuffer` slice as tensor storage.
 *
 * Logged under `BB.Q24B`.
 */
@RunWith(AndroidJUnit4::class)
class ExternalInitializerProbeTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Test
    fun doesAnExternallySuppliedInitializerReplaceTheSessionCopy() {
        val blob = File(app.filesDir, "weights.bin")
        assumeTrue("blob absent — launch the app once", blob.exists())

        // The source graph, whose external offsets are the ones dumped from the asset. Extracted under
        // its own name so nothing collides with the production cache.
        val graph = File(app.filesDir, "q24_decoder_init.onnx")
        if (!graph.exists()) {
            app.assets.open("decoder_init_int8.onnx").use { i ->
                graph.outputStream().use { o -> i.copyTo(o, 1 shl 20) }
            }
        }
        Log.i(TAG, "CONFIG graph=${graph.length()} blob=${blob.length()}")

        val baseline = measure("baseline") { open(graph, emptyMap()) }

        // Map exactly the embedding's region and hand it over as the initializer.
        val mapped = RandomAccessFile(blob, "r").use { raf ->
            raf.channel.use { it.map(FileChannel.MapMode.READ_ONLY, EMB_OFFSET, EMB_BYTES) }
        }
        val tensorHeapBefore = Debug.getNativeHeapAllocatedSize() / 1024
        val tensor = OnnxTensor.createTensor(env, mapped, longArrayOf(122672, 512), OnnxJavaType.UINT8)
        val tensorHeapAfter = Debug.getNativeHeapAllocatedSize() / 1024
        Log.i(TAG, "TENSOR create_heap_delta_kb=${tensorHeapAfter - tensorHeapBefore} (62,808,064 B region)")

        val supplied = measure("external_initializer") { open(graph, mapOf(EMB_NAME to tensor)) }
        tensor.close()

        Log.i(
            TAG,
            "VERDICT baseline_kb=$baseline supplied_kb=$supplied delta_kb=${baseline - supplied} " +
                "(a working mechanism saves about ${EMB_BYTES / 1024} kb)",
        )
    }

    private fun measure(label: String, build: () -> OrtSession): Long {
        System.gc()
        Thread.sleep(SETTLE_MS)
        val before = Debug.getNativeHeapAllocatedSize() / 1024
        val session = try {
            build()
        } catch (e: Throwable) {
            Log.i(TAG, "ARM $label FAILED ${e::class.java.simpleName}: ${e.message?.take(200)}")
            return -1
        }
        val delta = Debug.getNativeHeapAllocatedSize() / 1024 - before
        Log.i(TAG, "ARM $label heap_delta_kb=$delta")
        session.close()
        return delta
    }

    private fun open(f: File, external: Map<String, OnnxTensor>): OrtSession {
        val opts = ExecutionPolicy.current.toOptions().apply {
            setOptimizationLevel(OptLevel.NO_OPT)
            if (external.isNotEmpty()) addExternalInitializers(external)
        }
        return env.createSession(f.absolutePath, opts)
    }

    private companion object {
        const val TAG = "BB.Q24B"
        const val SETTLE_MS = 12_000L
        const val EMB_NAME = "decoder.embed_tokens.weight_quantized"
        const val EMB_OFFSET = 75_272_192L
        const val EMB_BYTES = 62_808_064L
    }
}
