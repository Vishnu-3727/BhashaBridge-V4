package com.bhashabridge.app.mt

import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Q22: 100 EN↔HI switches through the **real ownership path**, because that is where a native leak
 * would live.
 *
 * §3.24b fixed the peak — engines are evicted *before* the replacement is built, worth −36.7% of swap
 * peak — but it was measured over a handful of swaps. The failure this test exists to catch is the one
 * that only appears with repetition: a session, a mapping or an allocator arena that is not returned
 * each cycle and accumulates until the low-memory killer arrives. V3.4.1 shipped exactly that bug
 * (LESSONS_FROM_V3 L2), which is why the ownership rules exist at all.
 *
 * It drives `BhashaBridgeApp.translator(direction)` — **not** `MtEngine(...)` directly — so every cycle
 * exercises the real `evictOtherDirections` / borrow-lock machinery an Activity would. Each cycle
 * builds the engine for a direction, translates in it, and lets the *next* cycle's eviction release it.
 *
 * Recorded per cycle: total PSS, native PSS, native heap allocated, the process's own swap, mapped
 * model bytes, engine build time and translate latency. The pass conditions are the interesting part
 * and are asserted, not just logged:
 *
 *  - every cycle translates correctly in both directions (no silent degradation to `<unk>`);
 *  - no crash, and every engine build succeeds;
 *  - **memory is bounded** — the last decile's mean PSS may not exceed the first decile's by more than
 *    [DRIFT_LIMIT_MB], which is what a per-cycle leak would blow through;
 *  - native heap returns to a comparable level, checked the same way.
 *
 * ~100 × (build + translate) is several minutes; `-e cycles N` shortens it for a smoke run.
 *
 * Logged under `BB.Q22`.
 */
@RunWith(AndroidJUnit4::class)
class DirectionSwitchStressTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun oneHundredDirectionSwitchesDoNotLeak() {
        val cycles = InstrumentationRegistry.getArguments().getString("cycles")?.toIntOrNull() ?: CYCLES
        Log.i(TAG, "CONFIG cycles=$cycles")

        val pss = ArrayList<Long>(cycles)
        val nativeHeap = ArrayList<Long>(cycles)
        val buildMs = ArrayList<Long>(cycles)
        val translateMs = ArrayList<Long>(cycles)
        var failures = 0

        repeat(cycles) { i ->
            val direction = if (i % 2 == 0) Direction.EN_TO_HI else Direction.HI_TO_EN
            val sentence = if (direction == Direction.EN_TO_HI) EN else HI

            val t0 = System.nanoTime()
            val engine = app.translator(direction)
            val built = (System.nanoTime() - t0) / 1_000_000

            val t1 = System.nanoTime()
            val out = runCatching { engine.translate(sentence) }.getOrElse { e ->
                failures++
                Log.w(TAG, "CYCLE $i $direction FAILED: ${e::class.java.simpleName}: ${e.message}")
                ""
            }
            val translated = (System.nanoTime() - t1) / 1_000_000

            // A translation that returns nothing, or only unknown tokens, is a failure that would
            // otherwise pass silently — the engine "works", the output is garbage.
            if (out.isBlank() || out.contains("<unk>")) {
                failures++
                Log.w(TAG, "CYCLE $i $direction produced no usable output: '$out'")
            }

            val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
            val heapKb = Debug.getNativeHeapAllocatedSize() / 1024
            pss += mi.totalPss.toLong()
            nativeHeap += heapKb
            buildMs += built
            translateMs += translated

            if (i % LOG_EVERY == 0 || i == cycles - 1) {
                Log.i(
                    TAG,
                    "CYCLE $i $direction build_ms=$built translate_ms=$translated" +
                        " pss_kb=${mi.totalPss} native_pss_kb=${mi.nativePss}" +
                        " native_heap_kb=$heapKb swap_kb=${procSwapKb()}" +
                        " mapped_model_mb=${mappedModelBytes() / (1 shl 20)}" +
                        " out='${out.take(40)}'",
                )
            }
        }

        val decile = maxOf(1, cycles / 10)
        val firstPss = pss.take(decile).average()
        val lastPss = pss.takeLast(decile).average()
        val firstHeap = nativeHeap.take(decile).average()
        val lastHeap = nativeHeap.takeLast(decile).average()

        Log.i(TAG, "PSS first_decile_kb=${firstPss.toLong()} last_decile_kb=${lastPss.toLong()} " +
            "drift_mb=${((lastPss - firstPss) / 1024).toLong()} peak_kb=${pss.max()}")
        Log.i(TAG, "NATIVE_HEAP first_decile_kb=${firstHeap.toLong()} last_decile_kb=${lastHeap.toLong()} " +
            "drift_mb=${((lastHeap - firstHeap) / 1024).toLong()} peak_kb=${nativeHeap.max()}")
        Log.i(TAG, "BUILD ${Stats.of(buildMs).toJson()}")
        Log.i(TAG, "TRANSLATE ${Stats.of(translateMs).toJson()}")
        Log.i(TAG, "FAILURES $failures")

        assertEquals("every cycle must translate", 0, failures)
        assertTrue(
            "PSS drifted ${((lastPss - firstPss) / 1024).toLong()} MB across $cycles switches " +
                "(first decile ${(firstPss / 1024).toLong()} MB, last ${(lastPss / 1024).toLong()} MB)",
            lastPss - firstPss < DRIFT_LIMIT_MB * 1024,
        )
        assertTrue(
            "native heap drifted ${((lastHeap - firstHeap) / 1024).toLong()} MB across $cycles switches",
            lastHeap - firstHeap < DRIFT_LIMIT_MB * 1024,
        )
    }

    /** Address space of the model files this process has mapped — a leak shows here before PSS. */
    private fun mappedModelBytes(): Long = runCatching {
        var bytes = 0L
        File("/proc/self/maps").forEachLine { line ->
            if (line.contains("/com.bhashabridge") &&
                (line.contains(".onnx") || line.contains(".bin") || line.contains(".ort"))
            ) {
                val range = line.substringBefore(' ')
                bytes += range.substringAfter('-').toLong(16) - range.substringBefore('-').toLong(16)
            }
        }
        bytes
    }.getOrDefault(0L)

    private fun procSwapKb(): Long = runCatching {
        File("/proc/self/status").readLines()
            .first { it.startsWith("VmSwap") }
            .filter { it.isDigit() }.toLong()
    }.getOrDefault(-1L)

    private companion object {
        const val TAG = "BB.Q22"
        const val CYCLES = 100
        const val LOG_EVERY = 10

        /**
         * Headroom for one engine's worth of allocator noise, not for a leak. One direction costs
         * ~460 MB PSS, so a per-cycle leak of even 1% would exceed this within the run; genuine
         * allocator drift on this workload has measured in the tens of MB (§3.25).
         */
        const val DRIFT_LIMIT_MB = 150

        const val EN = "The weather is very nice today and I want to go outside."
        const val HI = "आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ।"
    }
}
