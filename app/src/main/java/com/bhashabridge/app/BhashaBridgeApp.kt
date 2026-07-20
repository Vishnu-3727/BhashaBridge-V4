package com.bhashabridge.app

import android.app.Application
import android.content.ComponentCallbacks2

/**
 * Purpose:  Process entry point, and the sole owner of every native resource in the app.
 * Owns:     Nothing yet. ONNX sessions arrive in Phase 4, Vosk models in Phase 9.
 * Lifetime: Process
 * Thread:   Main.
 *
 * This class is the answer to LESSONS_FROM_V3.md L2. In V3.4.1 the chain was
 * `Activity -> Translator -> OnnxSessionManager -> OrtSession`, with a creator at every level and
 * a destroyer at none: `OnnxSessionManager.release()` was correct code with zero call sites, so
 * every rotation leaked ~639 MB of native heap and re-paid an 8.6-second model load.
 *
 * The fix is structural, not vigilance. Native resources are owned here, at process scope, so an
 * Activity being destroyed cannot orphan them and a rotation cannot trigger a reload. Activities
 * and ViewModels borrow; they never release (R4.3).
 *
 * When models arrive, they attach here and nowhere else, and [onTrimMemory] becomes their release
 * trigger (R5.4) — at a ~639 MB footprint, returning memory when the OS asks is not optional.
 */
class BhashaBridgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        logDebug(LogTag.APP) { "Process started" }
    }

    /**
     * Release trigger for process-lifetime native resources.
     *
     * Wired now, with nothing to release, because the alternative is adding both the ONNX session
     * and its teardown path in the same commit — which is exactly the shape of the V3.4.1 defect
     * this class exists to prevent. R4.5 requires a `release()` to have a call site in the commit
     * that introduces it; having the call site already here means that rule is satisfiable rather
     * than merely stated.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            logDebug(LogTag.APP) { "Trim requested at level $level — nothing owned yet" }
        }
    }
}
