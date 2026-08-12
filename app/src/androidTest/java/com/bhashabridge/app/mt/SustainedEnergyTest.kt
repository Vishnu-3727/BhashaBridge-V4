package com.bhashabridge.app.mt

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.bench.SystemStats
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/**
 * Energy per translation, measured the only way this device permits.
 *
 * **What the hardware allows, measured first.** `/sys/class/power_supply/battery/` is SELinux-blocked
 * here, so everything comes through `BatteryManager`. Its charge counter moves in **4990 µAh steps
 * roughly every 10 s** on this part — about 20.5 mWh, or ~74 J, per tick. A single translation costs
 * a tiny fraction of one tick, so **per-translation energy cannot be read directly at any sample
 * rate**. The only honest instrument is a long sustained run whose total drain clears the quantum
 * many times over, with the device's idle draw subtracted:
 *
 * ```
 * energy/translation = ((Δcharge_busy − Δcharge_idle) × V_avg) / translations
 * ```
 *
 * Run both modes back to back at the same duration, same screen state, same starting temperature.
 *
 * **It refuses to produce a number while charging.** Charging current dwarfs the workload and would
 * turn the charge counter *upward*; a "measurement" taken on USB is not a weak result, it is a wrong
 * one. So the test waits for the unplug before starting, and if power returns mid-run it marks the
 * result `INVALID_REPLUGGED` and reports no energy. Screen state is deliberately left alone: keep it
 * on and identical across both arms, so the display's draw cancels in the subtraction rather than
 * risking the CPU suspending with the screen off.
 *
 * Drive it — start it, then unplug when the log says to:
 * ```
 * adb shell am instrument -w -e minutes 10 -e mode idle \
 *   -e class com.bhashabridge.app.mt.SustainedEnergyTest com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner
 * adb shell am instrument -w -e minutes 10 -e mode busy ...
 * ```
 */
@RunWith(AndroidJUnit4::class)
class SustainedEnergyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A fixed, mixed-length corpus. Same list in both arms, and the list to hand any other build. */
    private val corpus = listOf(
        "Water.",
        "Thank you.",
        "Where is the hospital?",
        "Hello, how are you?",
        "I need a doctor immediately.",
        "Please call an ambulance right now.",
        "The weather is very nice today and I want to go outside.",
        "Could you please tell me how to get to the railway station from here?",
    )

    @Test
    fun sustained() {
        val minutes = intArg("minutes", 10)
        val mode = argOf("mode") ?: "busy"
        val sampleMs = intArg("sampleMs", 15_000).toLong()

        Log.i(TAG, "CONFIG mode=$mode minutes=$minutes sampleMs=$sampleMs corpus=${corpus.size}")
        Log.i(TAG, "POLICY ${ExecutionPolicy.current.name} intra=${ExecutionPolicy.current.intraThreads} " +
            "kleidiAI=${if (ExecutionPolicy.current.disableKleidiAi) "OFF" else "on"}")

        // Build the engine BEFORE the measured window: model load is a one-off cost that would
        // otherwise be charged to the workload, and the idle arm does not pay it at all.
        val counter = CountingDecoder(GreedyDecoder())
        val engine = if (mode == "busy") MtEngine(context, Direction.EN_TO_HI, counter) else null
        try {
            engine?.let { repeat(3) { _ -> it.translate(corpus.last()) } }   // warm, still outside the window

            if (!awaitUnplugged()) {
                Log.w(TAG, "RESULT INVALID_STILL_PLUGGED — no energy number; the device never came off USB")
                return
            }

            val start = SystemStats.capture(context, "$mode-start")
            val startMs = System.currentTimeMillis()
            val deadline = startMs + minutes * 60_000L
            var nextSample = startMs + sampleMs

            val latencies = ArrayList<Long>()
            var translations = 0L
            var tokens = 0L
            var replugged = false
            val volts = ArrayList<Int>()
            val temps = ArrayList<Double>()
            val freqs = ArrayList<Long>()
            start.voltageMv?.let { volts += it }

            var i = 0
            while (System.currentTimeMillis() < deadline) {
                if (mode == "busy") {
                    val text = corpus[i++ % corpus.size]
                    val t0 = System.nanoTime()
                    engine!!.translate(text)
                    latencies += (System.nanoTime() - t0) / 1_000_000
                    translations++
                    tokens += counter.lastGenerated.toLong()
                } else {
                    Thread.sleep(200)
                }

                if (System.currentTimeMillis() >= nextSample) {
                    val s = SystemStats.capture(context, "$mode-t")
                    s.voltageMv?.let { volts += it }
                    s.batteryTempC?.let { temps += it }
                    s.perCoreFreqKhz?.let { f ->
                        val on = f.filter { it > 0 }
                        if (on.isNotEmpty()) freqs += (on.average() / 1000.0).toLong()
                    }
                    // Any return to power invalidates the arm: charging current swamps the signal.
                    if (s.batteryPlugged != null && s.batteryPlugged != "none") replugged = true
                    Log.i(
                        TAG,
                        "SAMPLE $mode t=${(System.currentTimeMillis() - startMs) / 1000}s " +
                            "cc=${s.chargeCounterUah} v=${s.voltageMv} temp=${s.batteryTempC} " +
                            "level=${s.batteryLevelPct} plugged=${s.batteryPlugged} " +
                            "translations=$translations freqMHz=${freqs.lastOrNull()}",
                    )
                    nextSample += sampleMs
                }
            }

            val end = SystemStats.capture(context, "$mode-end")
            end.voltageMv?.let { volts += it }
            val elapsedS = (System.currentTimeMillis() - startMs) / 1000.0

            val cc0 = start.chargeCounterUah
            val cc1 = end.chargeCounterUah
            val vAvg = if (volts.isEmpty()) 0.0 else volts.average() / 1000.0

            if (replugged || end.batteryPlugged != null && end.batteryPlugged != "none") {
                Log.w(TAG, "RESULT INVALID_REPLUGGED $mode — power returned during the run; no energy number")
            } else if (cc0 == null || cc1 == null) {
                Log.w(TAG, "RESULT INVALID_NO_COUNTER $mode — BatteryManager gave no charge counter")
            } else {
                val drainUah = cc0 - cc1                       // discharging ⇒ counter falls
                val energyJ = (drainUah / 1_000_000.0) * vAvg * 3600.0
                Log.i(
                    TAG,
                    "RESULT $mode elapsed=${f(elapsedS)}s drain_uAh=$drainUah quanta=${f(drainUah / 4990.0)} " +
                        "vAvg=${f(vAvg)}V energy_J=${f(energyJ)} mAh_per_hour=${f(drainUah / 1000.0 / (elapsedS / 3600.0))}",
                )
                if (mode == "busy" && translations > 0) {
                    Log.i(
                        TAG,
                        "WORKLOAD translations=$translations tokens=$tokens " +
                            "gross_J_per_translation=${f(energyJ / translations)} " +
                            "gross_J_per_1k_tokens=${f(energyJ / tokens * 1000)}",
                    )
                }
            }

            if (mode == "busy") {
                val st = Stats.of(latencies)
                Log.i(TAG, "LATENCY ${st.toJson()} translations=$translations tokens=$tokens " +
                    "throughput_tr_per_s=${f(translations / elapsedS)} tokens_per_s=${f(tokens / elapsedS)}")
                // Throughput per quarter: the sustained-behaviour readout that stands even if the
                // energy arm is invalidated.
                val q = latencies.size / 4
                if (q > 0) {
                    val quarters = (0 until 4).map { Stats.of(latencies.subList(it * q, (it + 1) * q)).median }
                    Log.i(TAG, "QUARTERS medianMs=${quarters.map { f(it) }} ratio_last_first=${f(quarters[3] / quarters[0])}")
                }
            }
            Log.i(TAG, "THERMAL $mode tempStart=${start.batteryTempC} tempEnd=${end.batteryTempC} " +
                "tempPeak=${temps.maxOrNull()} freqMHz=$freqs")
            Log.i(TAG, "LEVEL $mode start=${start.batteryLevelPct}% end=${end.batteryLevelPct}%")

            assertTrue("no work done", mode == "idle" || translations > 0)
        } finally {
            engine?.release()
        }
    }

    /**
     * Blocks until the device is off charge, up to three minutes, logging a prompt every 10 s.
     * Returns false if it never happens — the caller then reports no energy rather than a wrong one.
     */
    private fun awaitUnplugged(): Boolean {
        repeat(18) { i ->
            val s = SystemStats.capture(context, "plugcheck")
            val plugged = s.batteryPlugged
            if (plugged == null || plugged == "none") {
                Log.i(TAG, "UNPLUGGED — measurement window opens (temp=${s.batteryTempC}C level=${s.batteryLevelPct}%)")
                return true
            }
            Log.i(TAG, "WAITING_FOR_UNPLUG ${i * 10}s plugged=$plugged — unplug the cable now")
            Thread.sleep(10_000)
        }
        return false
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    private fun argOf(key: String): String? = InstrumentationRegistry.getArguments().getString(key)
    private fun intArg(key: String, default: Int): Int = argOf(key)?.toIntOrNull() ?: default

    private companion object {
        const val TAG = "BB_ENERGY"
    }
}
