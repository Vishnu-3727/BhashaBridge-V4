package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.SystemStats
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prices the single-engine eviction (OPTIMIZATION_SUMMARY §3.24b, queue item Q10).
 *
 * The claim being tested was arithmetic, not a measurement: one direction costs ~605-620 MB PSS
 * (§3.8/§3.9), so two must cost ~1.2 GB. This runs both states on one device in one process and
 * reports what they actually cost, plus what the eviction charges for being wrong — the reload the
 * user pays if they swap straight back.
 *
 * Both engines are constructed **directly** rather than through `BhashaBridgeApp.translator`, which
 * now evicts. That is the honest way to reproduce the pre-eviction state: two live `MtEngine`s is
 * exactly what `engines.getOrPut` used to hold.
 *
 * Requires the model assets and both directions' graphs. Numbers are logged under `BB.Footprint`,
 * one `REPORT` line per state, so a run is readable from `adb logcat -s BB.Footprint:I`.
 */
@RunWith(AndroidJUnit4::class)
class EngineFootprintTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun twoResidentEnginesVersusOne() {
        report("idle", SystemStats.capture(app, "idle"))

        val enHi = MtEngine(app, Direction.EN_TO_HI)
        enHi.translate(ENGLISH)
        val one = SystemStats.capture(app, "one_engine")
        report("one_engine", one)

        val hiEn = MtEngine(app, Direction.HI_TO_EN)
        hiEn.translate(HINDI)
        val two = SystemStats.capture(app, "two_engines")
        report("two_engines", two)

        val onePss = one.totalPssKb
        val twoPss = two.totalPssKb
        if (onePss != null && twoPss != null) {
            Log.i(TAG, "REPORT delta_pss_kb=${twoPss - onePss} ratio=${"%.2f".format(twoPss.toDouble() / onePss)}")
        }

        enHi.release()
        hiEn.release()
        // Captured three times, because the first run showed PSS still at 1.43 GB immediately after
        // both engines were closed. `release()` closes the ONNX sessions; whether the pages go back
        // to the OS is the native allocator's decision, and it is lazy. If the number does not fall
        // after a settle, the eviction returns memory to the *allocator* rather than to the system —
        // which is a materially weaker claim and has to be reported as such.
        report("released_immediate", SystemStats.capture(app, "released_immediate"))
        System.gc()
        Thread.sleep(3_000)
        report("released_after_3s_gc", SystemStats.capture(app, "released_after_3s_gc"))
        Thread.sleep(10_000)
        report("released_after_13s", SystemStats.capture(app, "released_after_13s"))

        // The assertion is deliberately weak — PSS on a live device is noisy and this test exists to
        // produce numbers, not to gate a build. It only fails if the second engine cost nothing,
        // which would mean the measurement itself is wrong.
        if (onePss != null && twoPss != null) {
            assertTrue(
                "a second resident engine must show up in PSS (one=$onePss kB, two=$twoPss kB)",
                twoPss > onePss + 100_000,
            )
        }
    }

    /**
     * What the eviction costs when the user swaps straight back: one full engine load.
     *
     * The trade this measures is a reload against ~600 MB of foreground memory. Both halves belong
     * in the ledger; only the memory half was argued when the change landed.
     */
    @Test
    fun swapEvictsAndTheReloadIsPriced() {
        val first = app.translator(Direction.EN_TO_HI)
        val afterFirst = SystemStats.capture(app, "after_first")
        report("after_first", afterFirst)

        // Sampled from another thread while the swap runs, because the number that matters is the
        // transient peak — the moment both engines could be resident — and a capture taken after
        // the swap returns cannot see it.
        val peak = java.util.concurrent.atomic.AtomicLong(0)
        val sampling = java.util.concurrent.atomic.AtomicBoolean(true)
        val sampler = Thread {
            while (sampling.get()) {
                SystemStats.capture(app, "peak").totalPssKb?.let { pss ->
                    peak.updateAndGet { maxOf(it, pss) }
                }
                Thread.sleep(200)
            }
        }.apply { isDaemon = true; start() }

        app.translator(Direction.HI_TO_EN)
        sampling.set(false)
        sampler.join(2_000)
        Log.i(TAG, "REPORT swap_peak_pss_kb=${peak.get()}")
        val afterSwap = SystemStats.capture(app, "after_swap")
        report("after_swap", afterSwap)

        val reloadStart = System.nanoTime()
        val reloaded = app.translator(Direction.EN_TO_HI)
        val reloadMs = (System.nanoTime() - reloadStart) / 1_000_000
        Log.i(TAG, "REPORT swap_back_reload_ms=$reloadMs")
        report("after_swap_back", SystemStats.capture(app, "after_swap_back"))

        assertNotSame("the evicted engine must be rebuilt, not returned", first, reloaded)
    }

    private fun report(label: String, s: SystemStats) {
        Log.i(
            TAG,
            "REPORT $label pss_kb=${s.totalPssKb} native_kb=${s.nativeHeapKb} " +
                "java_kb=${s.javaHeapKb} rss_kb=${s.rssKb} threads=${s.threadCount}",
        )
    }

    private companion object {
        const val TAG = "BB.Footprint"
        const val ENGLISH = "The weather is very nice today and I want to go outside."
        const val HINDI = "आज मौसम बहुत अच्छा है और मैं बाहर जाना चाहता हूँ।"
    }
}
