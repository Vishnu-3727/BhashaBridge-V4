package com.bhashabridge.app.mt

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

/**
 * Q24: `decoder_init` and `decoder_step` point at the **same bytes** in the shared blob (§3.30 —
 * `decoder_step` holds no unique tensor data at all). Do they also share those bytes in RAM?
 *
 * §3.50 says probably not: `weights.bin` maps 138.7 MB of address space at **RSS 0** while the process
 * holds ~410 MB of anonymous memory, which is what "ORT reads the initializers out of the file into
 * the session allocator" looks like. If each session does that independently, the identical decoder
 * weights are resident twice.
 *
 * The measurement is incremental and needs no new machinery: load one session, then the other, then
 * both, and watch native heap. If loading `decoder_step` after `decoder_init` costs as much as loading
 * it alone, nothing is shared. If it costs nothing, ORT is already deduplicating and Q24 is closed
 * before it starts.
 *
 * Sessions are built directly rather than through `MtEngine` because the point is to load *one graph
 * at a time*, which the engine deliberately never does.
 *
 * Logged under `BB.Q24`.
 */
@RunWith(AndroidJUnit4::class)
class SharedWeightRuntimeTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    @Test
    fun doTheTwoDecodersShareTheirIdenticalWeights() {
        val init = File(app.filesDir, "decoder_init_int8.opt.onnx")
        val step = File(app.filesDir, "decoder_step_int8.opt.onnx")
        val enc = File(app.filesDir, "encoder_int8.opt.onnx")
        assumeTrue(
            "baked graphs absent — launch the app once so the production cache exists",
            init.exists() && step.exists() && enc.exists(),
        )
        Log.i(TAG, "SIZES init=${init.length()} step=${step.length()} enc=${enc.length()} " +
            "blob=${File(app.filesDir, "weights.bin").length()}")

        // Each arm in its own settled state: the allocator hands pages back lazily (§3.25), so a
        // measurement taken immediately after a close reads the previous arm, not this one.
        val alone = mutableMapOf<String, Long>()
        for ((label, f) in listOf("decoder_init" to init, "decoder_step" to step, "encoder" to enc)) {
            alone[label] = measure(label) { open(f) }
        }

        // The question: does the second decoder cost its own weights again?
        val both = measure("init+step") { listOf(open(init), open(step)) }
        val all3 = measure("all_three") { listOf(open(init), open(step), open(enc)) }

        val sumInitStep = alone.getValue("decoder_init") + alone.getValue("decoder_step")
        val sumAll = sumInitStep + alone.getValue("encoder")
        Log.i(
            TAG,
            "VERDICT init_alone=${alone["decoder_init"]}kb step_alone=${alone["decoder_step"]}kb " +
                "enc_alone=${alone["encoder"]}kb sum_init_step=${sumInitStep}kb both=${both}kb " +
                "sum_all=${sumAll}kb all3=${all3}kb " +
                "shared_saving_kb=${sumInitStep - both}",
        )
    }

    /** Native heap growth, in KB, caused by [build] — measured settled, and released after. */
    private fun measure(label: String, build: () -> Any): Long {
        settle()
        val before = Debug.getNativeHeapAllocatedSize() / 1024
        val anonBefore = rssAnonKb()
        val held = build()
        val after = Debug.getNativeHeapAllocatedSize() / 1024
        val anonAfter = rssAnonKb()
        Log.i(TAG, "ARM $label heap_delta_kb=${after - before} anon_delta_kb=${anonAfter - anonBefore}")
        when (held) {
            is OrtSession -> held.close()
            is List<*> -> held.forEach { (it as OrtSession).close() }
        }
        return after - before
    }

    private fun open(f: File): OrtSession =
        env.createSession(
            f.absolutePath,
            ExecutionPolicy.current.toOptions().apply { setOptimizationLevel(OptLevel.NO_OPT) },
        )

    private fun settle() {
        System.gc()
        Thread.sleep(SETTLE_MS) // §3.25: the allocator returns pages ~10 s after a release
    }

    private fun rssAnonKb(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("RssAnon") }
            .filter { it.isDigit() }.toLong()
    }.getOrDefault(-1L)

    private companion object {
        const val TAG = "BB.Q24"
        const val SETTLE_MS = 12_000L
    }
}
