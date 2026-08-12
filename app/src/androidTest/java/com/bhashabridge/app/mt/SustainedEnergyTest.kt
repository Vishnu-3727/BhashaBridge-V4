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

    /**
     * How many tokens one pass through [corpus] generates — the denominator for energy per token.
     *
     * Runs **plugged in**, because it measures nothing electrical: greedy decoding is deterministic
     * and parity-exact across every configuration this project has measured, so the token count for a
     * given sentence is a fixed property of the model, not of the run that observed it. That makes it
     * recoverable after the fact, which is how the first energy cycle's missing denominator gets
     * filled without asking anyone to pull the cable again.
     */
    @Test
    fun corpusTokens() {
        val counter = CountingDecoder(GreedyDecoder())
        val engine = MtEngine(context, Direction.EN_TO_HI, counter)
        try {
            repeat(2) { engine.translate(corpus.first()) }
            var total = 0L
            corpus.forEachIndexed { i, sentence ->
                val out = engine.translate(sentence)
                val t = counter.lastGenerated
                total += t
                Log.i(TAG, "CORPUS_TOKENS[$i] tokens=$t \"$sentence\" -> \"$out\"")
            }
            Log.i(TAG, "CORPUS_TOKENS_TOTAL perCycle=$total sentences=${corpus.size} " +
                "meanPerTranslation=${f(total.toDouble() / corpus.size)}")
        } finally {
            engine.release()
        }
    }

    /**
     * The whole measurement in **one** invocation and **one** unplug window: idle → busy → idle.
     *
     * Three chained `am instrument` calls meant three independent gates, and that is exactly how the
     * first real attempt was lost — arm one's gate opened during a previous step's unplug, the cable
     * came back, and the remaining arms found it plugged. One window cannot desynchronise from the
     * operator, the two idle arms bracket the busy one so baseline drift is visible, and the arithmetic
     * that turns three drains into a per-translation figure happens here rather than by hand across
     * three log blocks.
     *
     * The gate also requires the device to have been off charge for [SETTLE_CHECKS] consecutive
     * checks, so a brief unplug left over from an earlier step cannot start the run.
     */
    @Test
    fun cycle() {
        val idleMin = intArg("idleMinutes", 8)
        val busyMin = intArg("busyMinutes", 14)
        val sampleMs = intArg("sampleMs", 15_000).toLong()

        Log.i(TAG, "CYCLE_CONFIG idle=${idleMin}m busy=${busyMin}m idle=${idleMin}m corpus=${corpus.size}")
        Log.i(TAG, "POLICY ${ExecutionPolicy.current.name} intra=${ExecutionPolicy.current.intraThreads} " +
            "kleidiAI=${if (ExecutionPolicy.current.disableKleidiAi) "OFF" else "on"}")

        val counter = CountingDecoder(GreedyDecoder())
        val engine = MtEngine(context, Direction.EN_TO_HI, counter)
        try {
            repeat(3) { engine.translate(corpus.last()) }        // warm, outside every window

            if (!awaitUnplugged()) {
                Log.w(TAG, "RESULT INVALID_STILL_PLUGGED — no energy number; the device never came off USB")
                return
            }

            val a = phase("idle1", idleMin, sampleMs, null, null)
            val b = phase("busy", busyMin, sampleMs, engine, counter)
            val c = phase("idle2", idleMin, sampleMs, null, null)

            if (a == null || b == null || c == null) {
                Log.w(TAG, "RESULT INVALID_REPLUGGED — power returned during the cycle; no energy number")
                return
            }

            // Idle draw is measured on both sides of the workload and averaged, so a baseline that
            // drifts with temperature is halved rather than picked from whichever end suits.
            val idleRateUahPerS = (a.drainUah + c.drainUah).toDouble() / (a.seconds + c.seconds)
            val busyRateUahPerS = b.drainUah / b.seconds
            val attributableUah = (busyRateUahPerS - idleRateUahPerS) * b.seconds
            val vAvg = (a.vAvg + b.vAvg + c.vAvg) / 3.0
            val energyJ = (attributableUah / 1_000_000.0) * vAvg * 3600.0

            Log.i(TAG, "IDLE_BRACKET first=${f(a.drainUah / a.seconds * 3600)}uAh/h last=${f(c.drainUah / c.seconds * 3600)}uAh/h " +
                "drift=${f(if (a.drainUah > 0) c.drainUah / a.drainUah.toDouble() else 0.0)}")
            Log.i(TAG, "CYCLE_RESULT busy_uAh=${b.drainUah} idle_rate_uAh_per_h=${f(idleRateUahPerS * 3600)} " +
                "attributable_uAh=${f(attributableUah)} quanta=${f(attributableUah / QUANTUM_UAH)} vAvg=${f(vAvg)}V " +
                "energy_J=${f(energyJ)}")
            if (b.translations > 0) {
                Log.i(TAG, "ENERGY_PER_TRANSLATION J=${f(energyJ / b.translations)} " +
                    "translations=${b.translations} tokens=${b.tokens} " +
                    "J_per_1k_tokens=${f(energyJ / b.tokens * 1000)} " +
                    "quantisation_error_pct=${f(100.0 * QUANTUM_UAH / attributableUah)}")
            }
        } finally {
            engine.release()
        }
    }

    /** One measured phase. Returns null if power came back, which voids the whole cycle. */
    private fun phase(
        name: String,
        minutes: Int,
        sampleMs: Long,
        engine: MtEngine?,
        counter: CountingDecoder?,
    ): Phase? {
        val start = SystemStats.capture(context, "$name-start")
        val startMs = System.currentTimeMillis()
        val deadline = startMs + minutes * 60_000L
        var nextSample = startMs + sampleMs
        val volts = ArrayList<Int>().apply { start.voltageMv?.let { add(it) } }
        val latencies = ArrayList<Long>()
        var translations = 0L
        var tokens = 0L
        var i = 0

        while (System.currentTimeMillis() < deadline) {
            if (engine != null) {
                val t0 = System.nanoTime()
                engine.translate(corpus[i++ % corpus.size])
                latencies += (System.nanoTime() - t0) / 1_000_000
                translations++
                tokens += (counter?.lastGenerated ?: 0).toLong()
            } else {
                Thread.sleep(200)
            }
            if (System.currentTimeMillis() >= nextSample) {
                val s = SystemStats.capture(context, name)
                s.voltageMv?.let { volts += it }
                if (!isUnplugged(s.batteryPlugged)) {
                    Log.w(TAG, "REPLUGGED during $name — cycle void")
                    return null
                }
                Log.i(TAG, "SAMPLE $name t=${(System.currentTimeMillis() - startMs) / 1000}s cc=${s.chargeCounterUah} " +
                    "v=${s.voltageMv} temp=${s.batteryTempC} level=${s.batteryLevelPct} translations=$translations")
                nextSample += sampleMs
            }
        }

        val end = SystemStats.capture(context, "$name-end")
        end.voltageMv?.let { volts += it }
        if (!isUnplugged(end.batteryPlugged)) return null
        val cc0 = start.chargeCounterUah ?: return null
        val cc1 = end.chargeCounterUah ?: return null
        val seconds = (System.currentTimeMillis() - startMs) / 1000.0
        val p = Phase(name, cc0 - cc1, seconds, volts.average() / 1000.0, translations, tokens)
        // tokens on this line too: the first cycle's CYCLE_RESULT was voided by a replug and took the
        // only record of the token count with it, leaving a per-translation figure that could not be
        // converted to per-token. A phase should carry its own denominator.
        Log.i(TAG, "PHASE $name drain_uAh=${p.drainUah} seconds=${f(seconds)} translations=$translations " +
            "tokens=$tokens tempStart=${start.batteryTempC} tempEnd=${end.batteryTempC}")
        if (latencies.isNotEmpty()) {
            Log.i(TAG, "PHASE_LATENCY $name ${Stats.of(latencies).toJson()} " +
                "throughput_tr_per_s=${f(translations / seconds)}")
        }
        return p
    }

    private class Phase(
        val name: String, val drainUah: Long, val seconds: Double,
        val vAvg: Double, val translations: Long, val tokens: Long,
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
                    if (!isUnplugged(s.batteryPlugged)) replugged = true
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

            if (replugged || !isUnplugged(end.batteryPlugged)) {
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
        var settled = 0
        // Sized for a human, not a script: the operator may be several minutes away from the phone.
        // A five-minute gate expired before the cable came out once, wasting a launch.
        repeat(intArg("waitMinutes", 30) * 6) { i ->
            val s = SystemStats.capture(context, "plugcheck")
            val plugged = s.batteryPlugged
            if (isUnplugged(plugged)) {
                // Require several consecutive off-charge checks. A brief unplug left over from an
                // earlier step must not open the window — that is precisely how one run was lost.
                settled++
                if (settled >= SETTLE_CHECKS) {
                    Log.i(TAG, "UNPLUGGED and settled — measurement window opens " +
                        "(temp=${s.batteryTempC}C level=${s.batteryLevelPct}%)")
                    return true
                }
                Log.i(TAG, "UNPLUGGED ${settled}/$SETTLE_CHECKS — hold, confirming it is deliberate")
                Thread.sleep(10_000)
                return@repeat
            }
            settled = 0
            if (plugged !in KNOWN_PLUGGED_STATES) {
                // Fail loudly rather than spin for three minutes against a value we do not model —
                // which is exactly how the first attempt at this run was lost.
                Log.w(TAG, "UNKNOWN_PLUG_STATE '$plugged' — not one of $KNOWN_PLUGGED_STATES; refusing to guess")
                return false
            }
            Log.i(TAG, "WAITING_FOR_UNPLUG ${i * 10}s plugged=$plugged — unplug the cable now")
            Thread.sleep(10_000)
        }
        return false
    }

    /**
     * The one place this test decides what "on battery" means.
     *
     * The string comes from `SystemStats.pluggedName`, where `BatteryManager.EXTRA_PLUGGED == 0` maps
     * to **`"unplugged"`**. An earlier version of this file compared against `"none"` — a value that
     * exists nowhere — so the gate never opened, every arm reported `INVALID_STILL_PLUGGED` while the
     * cable was actually out, and a 30-minute measurement was lost. A null is *unknown*, not
     * unplugged: refusing it is what keeps a failed read from being reported as an energy number.
     */
    private fun isUnplugged(plugged: String?): Boolean = plugged == "unplugged"

    private fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)
    private fun argOf(key: String): String? = InstrumentationRegistry.getArguments().getString(key)
    private fun intArg(key: String, default: Int): Int = argOf(key)?.toIntOrNull() ?: default

    private companion object {
        const val TAG = "BB_ENERGY"
        /** Every value `SystemStats.pluggedName` can produce; anything else means the model is stale. */
        val KNOWN_PLUGGED_STATES = setOf("unplugged", "ac", "usb", "wireless", "unknown")
        /** Consecutive off-charge checks, 10 s apart, before the window opens. */
        const val SETTLE_CHECKS = 3
        /** Measured on the SM-M315F: the charge counter moves in steps of this size. */
        const val QUANTUM_UAH = 5361.0
    }
}
