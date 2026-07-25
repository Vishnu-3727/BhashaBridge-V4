package com.bhashabridge.app.mt

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P8 operator-level profiling harness (opt-in; NOT part of the default benchmark). It builds a
 * production-configured engine with ONNX Runtime's built-in profiler enabled, drives the real
 * translate() path so the encoder / decoder_init / decoder_step graphs execute exactly as they do in
 * the app, then flushes ORT's Chrome-trace JSON to external storage for offline analysis with
 * model_pipeline/ort_profile_report.py.
 *
 * It measures the *steady-state* decode, not startup: it warms the `.ort` cache first (so the profiled
 * build hits the production NO_OPT + mmap path, not a one-time bake) and runs warmup translations
 * before the measured ones (so per-node allocation jitter of the first run does not dominate the
 * trace). The profiler adds real overhead, so this runs only here, behind the profileDir opt-in — the
 * default benchmark ([BenchmarkSuiteTest]) never enables it and is unaffected.
 *
 * Run:  ./gradlew :app:connectedDebugAndroidTest \
 *          -Pandroid.testInstrumentationRunnerArguments.class=com.bhashabridge.app.mt.OrtProfilingTest
 * Then: adb pull /sdcard/Android/data/com.bhashabridge.app/files/ort-profile ./
 *       python model_pipeline/ort_profile_report.py ort-profile/ort_decoder_step_*.json
 */
@RunWith(AndroidJUnit4::class)
class OrtProfilingTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val direction = Direction.EN_TO_HI
    private val sentence = "The weather is very nice today and I want to go outside."
    private val warmup = 5
    private val measured = 30

    @Test
    fun profileSteadyStateDecode() {
        val dir = File(context.getExternalFilesDir(null), "ort-profile").apply {
            mkdirs()
            listFiles()?.forEach { it.delete() } // start clean so pulled files are only this run
        }

        // Warm the .ort cache so the profiled engine loads the production mmap graph, not a cold bake.
        OnnxModels(context, direction).release()

        val tune = ExecutionPolicy.current.copy(profileDir = dir.absolutePath)
        Log.i(TAG, "profiling with policy=${tune.name} intra=${tune.intraThreads} affinity=${tune.intraOpAffinities ?: "OFF"}")
        val engine = MtEngine(context, direction, tune = tune)

        val paths = try {
            repeat(warmup) { engine.translate(sentence) }
            repeat(measured) { engine.translate(sentence) }
            engine.endProfiling()
        } finally {
            engine.release()
        }

        paths.forEach { Log.i(TAG, "ORT_PROFILE_FILE $it") }
        assertTrue("profiler produced no trace files", paths.isNotEmpty())
        assertTrue("trace files missing on disk", paths.all { File(it).exists() && File(it).length() > 0 })
    }

    private companion object {
        const val TAG = "BB.Profile"
    }
}
