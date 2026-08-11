package com.bhashabridge.app.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Debug
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import java.io.File

/**
 * Does the speech path leak when it is used the way a user uses it — over and over?
 *
 * Every existing speech test runs one session. One session cannot fail this way: a `Recognizer`, an
 * `AudioRecord`, three audio effects, a `MediaCodec` and a `MediaExtractor` that are each acquired
 * and released once look identical whether or not the release actually worked. Only repetition
 * separates them, which is why the resource-lifecycle audit could close every hole it found by
 * reading and still not know the answer.
 *
 * **File descriptors are the sharp instrument here, not memory.** `AudioRecord`, each audio effect,
 * the codec and the extractor all hold kernel objects with an fd apiece; a missed release shows up as
 * a straight line in `/proc/self/fd` with no allocator, no GC and no page cache to blur it. Native
 * heap is reported too, but asserted loosely — jemalloc returns pages to the OS lazily and on its own
 * schedule (§3.25 measured exactly that), so a tight bound there would fail honestly-clean runs.
 *
 * The model is loaded once and shared by every cycle, matching production: `BhashaBridgeApp` owns it
 * at process scope. A test that reloaded it per cycle would be measuring the model loader.
 */
@RunWith(AndroidJUnit4::class)
class SpeechChurnLeakTest {

    @Test
    fun fileTranscriptionChurnDoesNotLeak() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val uri = Uri.fromFile(copyFixture(context))
        val model = sharedModel()

        churn("file_transcription", WARMUP, CYCLES) {
            AudioFileTranscriber.transcribe(context, uri, model).collect { }
        }
    }

    /**
     * The microphone path, which the file path does not exercise: `AudioRecord`, the three platform
     * effects, and the generation-counter stop protocol.
     *
     * Recording silence is the point, not a limitation — the resources being counted are acquired and
     * released identically whether or not anyone speaks, and a test that needed a person to talk into
     * the phone could not run.
     */
    @Test
    fun microphoneSessionChurnDoesNotLeak() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantMicrophone(context)
        assertTrue(
            "RECORD_AUDIO was not granted; AudioRecord would silently yield silence",
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val model = sharedModel()

        churn("microphone_session", MIC_WARMUP, MIC_CYCLES) {
            // A fresh AudioCapture per cycle, because that is not what production does — the
            // ViewModel keeps one for its whole life — and the harsher shape is the one worth
            // proving. A per-session leak hides inside a reused object's steady state.
            val capture = AudioCapture()
            withTimeout(SESSION_TIMEOUT_MS) {
                val session = launch(Dispatchers.Default) { capture.record(model).collect { } }
                delay(SESSION_MS)
                capture.stop()
                session.join()
            }
        }
    }

    /**
     * Runs [block] [warmup] times untimed, takes a baseline, runs it [cycles] more, and reports the
     * drift per cycle.
     *
     * The warm-up is load-bearing. The first pass through any of this allocates thread pools, native
     * scratch buffers, JIT profiles and a page cache that never come back — charging those to "the
     * leak" would condemn every clean implementation. What a leak looks like is growth that continues
     * *after* the one-time costs are paid, which is the only interval measured.
     */
    private suspend fun churn(name: String, warmup: Int, cycles: Int, block: suspend () -> Unit) {
        repeat(warmup) { block() }
        val before = Probe.take()
        Log.i(TAG, "$name baseline $before")

        // Probed in quarters, not just at the end. A total is not a shape: 2 MB of growth that
        // flattens is an allocator settling, and 2 MB that keeps climbing at the same rate is a
        // leak, and the two are indistinguishable from before/after numbers alone.
        val step = maxOf(1, cycles / 4)
        repeat(cycles) { i ->
            block()
            if ((i + 1) % step == 0 && i + 1 < cycles) {
                Log.i(TAG, "$name at ${i + 1} cycles ${Probe.take()}")
            }
        }

        val after = Probe.take()
        Log.i(TAG, "$name after ${cycles} cycles $after")

        val fdDelta = after.fds - before.fds
        val threadDelta = after.threads - before.threads
        val nativeDeltaKb = after.nativeHeapKb - before.nativeHeapKb
        Log.i(
            TAG,
            "$name DELTA fds=$fdDelta threads=$threadDelta nativeKb=$nativeDeltaKb " +
                "pssKb=${after.pssKb - before.pssKb} perCycleFds=${fdDelta.toDouble() / cycles}",
        )

        // One descriptor per cycle is the signature of a missed release and is what this exists to
        // catch. The small slack absorbs the unrelated fds the platform opens on its own schedule —
        // a binder connection, a lazily-mapped font — which do not scale with the loop.
        assertTrue(
            "$name leaked file descriptors: +$fdDelta over $cycles cycles " +
                "(${before.fds} -> ${after.fds})",
            fdDelta <= FD_SLACK,
        )
        assertTrue(
            "$name leaked threads: +$threadDelta over $cycles cycles " +
                "(${before.threads} -> ${after.threads})",
            threadDelta <= THREAD_SLACK,
        )
        // Deliberately generous: this catches a Recognizer or a decoded PCM buffer retained per
        // cycle (megabytes each), not allocator hysteresis.
        assertTrue(
            "$name native heap grew ${nativeDeltaKb} KB over $cycles cycles " +
                "(${nativeDeltaKb / cycles} KB/cycle)",
            nativeDeltaKb / cycles <= NATIVE_KB_PER_CYCLE,
        )
    }

    /** Open descriptors, live threads and native heap — the three things a missed release moves. */
    private class Probe(val fds: Int, val threads: Int, val nativeHeapKb: Long, val pssKb: Long) {
        override fun toString() = "fds=$fds threads=$threads nativeKb=$nativeHeapKb pssKb=$pssKb"

        companion object {
            fun take(): Probe {
                // Collect and settle first: a Recognizer whose only reference has just gone out of
                // scope is not a leak, and without this the last cycle's garbage reads as one.
                Runtime.getRuntime().gc()
                Thread.sleep(SETTLE_MS)
                Runtime.getRuntime().gc()
                Thread.sleep(SETTLE_MS)
                val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                return Probe(
                    fds = File("/proc/self/fd").list()?.size ?: -1,
                    threads = Thread.getAllStackTraces().size,
                    nativeHeapKb = Debug.getNativeHeapAllocatedSize() / 1024,
                    pssKb = info.totalPss.toLong(),
                )
            }
        }
    }

    private fun grantMicrophone(context: Context) {
        // uiAutomation rather than GrantPermissionRule: no extra test dependency, and it is a no-op
        // when the permission is already held.
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation
                .grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun copyFixture(context: Context): File {
        val target = File(context.cacheDir, FIXTURE)
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    companion object {
        private const val TAG = "BB_SPEECH_LEAK"
        private const val FIXTURE = "speech_i_need_water.wav"

        private const val WARMUP = 3
        private const val CYCLES = 20
        private const val MIC_WARMUP = 2

        /**
         * More cycles than the file path gets, because the microphone path is the one that showed
         * native-heap growth at all and a short run cannot say whether it flattens. At ~1 s per
         * session this still costs well under a minute.
         */
        private const val MIC_CYCLES = 32
        private const val SESSION_MS = 350L
        private const val SESSION_TIMEOUT_MS = 15_000L
        private const val SETTLE_MS = 250L

        private const val FD_SLACK = 4
        private const val THREAD_SLACK = 2
        private const val NATIVE_KB_PER_CYCLE = 256

        /**
         * Loaded once for the whole class and never released: both tests share it, and closing it
         * while the other test's session still held it would be the very use-after-free
         * `withSpeechModel` exists to prevent. The process ends when the class does.
         */
        private var loaded: Model? = null

        @JvmStatic
        @BeforeClass
        fun loadModel() {
            val context = ApplicationProvider.getApplicationContext<Context>()
            loaded = VoskModels(context).model(Direction.EN_TO_HI)
        }

        private fun sharedModel(): Model = loaded ?: error("model was not loaded")
    }
}
