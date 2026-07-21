package com.bhashabridge.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import android.os.SystemClock
import com.bhashabridge.app.bench.Metrics
import com.bhashabridge.app.mt.MtEngine
import com.bhashabridge.app.speech.VoskModels

/**
 * Purpose:  Process entry point, and the sole owner of every native resource in the app.
 * Owns:     One [MtEngine] per [Direction] and the [VoskModels], all created lazily.
 * Lifetime: Process
 * Thread:   Main; [translator] is synchronised so a background translate thread can create engines.
 *
 * This class is the answer to LESSONS_FROM_V3.md L2. In V3.4.1 the chain was
 * `Activity -> Translator -> OnnxSessionManager -> OrtSession`, with a creator at every level and
 * a destroyer at none: `OnnxSessionManager.release()` was correct code with zero call sites, so
 * every rotation leaked ~639 MB of native heap and re-paid an 8.6-second model load.
 *
 * The fix is structural, not vigilance. Native resources are owned here, at process scope, so an
 * Activity being destroyed cannot orphan them and a rotation cannot trigger a reload. Activities
 * and ViewModels borrow via [translator]; they never construct or release an [MtEngine] (R4.3/R4.4).
 * [onTrimMemory] is the single release trigger (R5.4) — at a ~639 MB footprint, returning memory
 * when the OS asks is not optional.
 */
class BhashaBridgeApp : Application() {

    private val engines = HashMap<Direction, MtEngine>()

    /**
     * The Vosk acoustic models, owned here for the same reason the MT engines are: large native
     * allocations that must survive a rotation and must have exactly one release trigger. Loading
     * is lazy inside [VoskModels] too, so a session that never uses the mic never pays for it.
     */
    private val speechModelsLazy = lazy { VoskModels(this) }
    val speechModels: VoskModels get() = speechModelsLazy.value

    override fun onCreate() {
        super.onCreate()
        // Phase 11A: how much of startup is spent before a line of this app's code runs — process
        // fork, ART class loading, native library mapping. Measured, not assumed.
        logDebug(LogTag.APP) {
            val sinceFork = SystemClock.uptimeMillis() - Process.getStartUptimeMillis()
            "Process started (${sinceFork} ms after fork)"
        }
    }

    /**
     * The borrow point: returns the process-scoped engine for [direction], constructing it on first
     * use (session load blocks — call off the main thread). Callers use it; they never release it.
     */
    @Synchronized
    fun translator(direction: Direction): MtEngine =
        engines.getOrPut(direction) {
            logDebug(LogTag.APP) { "Loading MT engine: $direction" }
            // Phase 11A: one measured run around the whole construction. The stage marks inside
            // Tokenizer and OnnxModels attribute the time to tokenizer / verify / extract / session,
            // so the startup cost is broken down rather than reported as a single number.
            Metrics.begin("engine_init")
            MtEngine(this, direction).also {
                Metrics.counter("direction_en_hi", if (direction == Direction.EN_TO_HI) 1 else 0)
                Metrics.end()
            }
        }

    /**
     * The one release trigger for every process-lifetime native resource — ONNX sessions and Vosk
     * models alike. R4.5: `release()` and its call site land in the same commit, which is the
     * structural guard against the v3.4.1 leak where teardown existed but was never called.
     *
     * Safe because it only fires at TRIM_MEMORY_COMPLETE — the app is backgrounded and a kill
     * candidate — and the UI stops any recording session in `onStop`, before that can happen.
     */
    @Synchronized
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_COMPLETE) return
        if (engines.isNotEmpty()) {
            logDebug(LogTag.APP) { "Trim level $level — releasing ${engines.size} engine(s)" }
            engines.values.forEach { it.release() }
            engines.clear()
        }
        // Guarded, not `speechModels.release()`: touching the property would *construct* the
        // models we are trying to avoid holding.
        if (speechModelsLazy.isInitialized()) {
            logDebug(LogTag.APP) { "Trim level $level — releasing speech models" }
            speechModelsLazy.value.release()
        }
    }
}
