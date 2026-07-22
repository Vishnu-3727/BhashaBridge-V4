package com.bhashabridge.app.mt

import android.content.Context
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 11C thread-safety evidence for the concurrent session loader.
 *
 * Three loads run on three threads inside `OnnxModels`'s constructor. This test drives that path
 * repeatedly and asserts the properties that concurrency could plausibly break: the object is fully
 * and identically built every time, no loader thread outlives the constructor, and native memory
 * returns to its baseline after `release()`.
 *
 * A deadlock shows up as the instrumentation timeout rather than an assertion.
 */
@RunWith(AndroidJUnit4::class)
class ParallelSessionLoadTest {

    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun loadsDeterministicallyAndCleansUp() {
        assertEquals("loader threads leaked before the test started", 0, loaderThreads())

        val baselineNative = Debug.getNativeHeapAllocatedSize()
        var firstNames: List<String>? = null

        // Three full construct/release cycles. A race in the loader — a session assigned to the wrong
        // field, a partially published object, a duplicated load — would show up as a differing cache
        // contract or a failure on one of the iterations rather than all of them.
        repeat(CYCLES) { cycle ->
            val started = System.nanoTime()
            val models = OnnxModels(app, Direction.EN_TO_HI)
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            try {
                // 18 layers x 4 cache tensors: the Phase 6A contract, verified per cycle.
                assertEquals("cache tensor count changed", 72, models.pastInputNames.size)
                firstNames?.let { assertEquals("cache ordering changed", it, models.pastInputNames) }
                firstNames = models.pastInputNames

                // Ownership: each field must hold a distinct, usable session.
                val sessions = listOf(
                    models.encoderSession(), models.decoderInitSession(), models.decoderStepSession()
                )
                assertEquals("two fields hold the same session", 3, sessions.distinct().size)
                assertTrue("encoder inputs missing", models.encoderSession().inputInfo.containsKey("input_ids"))
                assertTrue(
                    "decoder_step must not expose encoder_hidden_states",
                    !models.decoderStepSession().inputInfo.containsKey("encoder_hidden_states"),
                )

                assertEquals("loader thread outlived the constructor", 0, loaderThreads())
                Log.i(TAG, "CYCLE ${cycle + 1} construct_ms=$elapsedMs loader_threads_after=${loaderThreads()}")
            } finally {
                models.release()
            }
        }

        // Deterministic shutdown: the pool is shut down in a finally, and its threads are daemons, so
        // nothing from the loader survives its constructor.
        assertEquals("loader threads survived all cycles", 0, loaderThreads())

        // Leak check: native heap after three construct/release cycles, against the baseline. ONNX
        // Runtime keeps process-wide allocations (the environment, arenas), so this is a bound on
        // drift, not an equality — a leaked session would be hundreds of MB.
        val drift = Debug.getNativeHeapAllocatedSize() - baselineNative
        Log.i(TAG, "NATIVE_DRIFT after $CYCLES cycles = ${drift / (1024 * 1024)} MB")
        assertTrue("native heap grew by ${drift / (1024 * 1024)} MB — session leak", drift < LEAK_LIMIT)
    }

    private fun loaderThreads(): Int =
        Thread.getAllStackTraces().keys.count { it.name.startsWith("bb-session-load") && it.isAlive }

    private companion object {
        const val TAG = "BB_PARALLEL_LOAD"
        const val CYCLES = 3
        /** One leaked session is ~75–204 MB; 64 MB of allocator drift is well below that. */
        const val LEAK_LIMIT = 64L * 1024 * 1024
    }
}
