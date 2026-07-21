package com.bhashabridge.app

import android.app.Application
import android.content.ComponentCallbacks2
import com.bhashabridge.app.mt.MtEngine

/**
 * Purpose:  Process entry point, and the sole owner of every native resource in the app.
 * Owns:     One [MtEngine] per [Direction], created lazily. Vosk models arrive in Phase 9.
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

    override fun onCreate() {
        super.onCreate()
        logDebug(LogTag.APP) { "Process started" }
    }

    /**
     * The borrow point: returns the process-scoped engine for [direction], constructing it on first
     * use (session load blocks — call off the main thread). Callers use it; they never release it.
     */
    @Synchronized
    fun translator(direction: Direction): MtEngine =
        engines.getOrPut(direction) {
            logDebug(LogTag.APP) { "Loading MT engine: $direction" }
            MtEngine(this, direction)
        }

    /**
     * Release trigger for the process-lifetime ONNX sessions. R4.5: the `release()` introduced with
     * [MtEngine] has its call site here, in the same commit — the structural guard against the
     * V3.4.1 leak, where teardown existed but was never called.
     */
    @Synchronized
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE && engines.isNotEmpty()) {
            logDebug(LogTag.APP) { "Trim level $level — releasing ${engines.size} engine(s)" }
            engines.values.forEach { it.release() }
            engines.clear()
        }
    }
}
