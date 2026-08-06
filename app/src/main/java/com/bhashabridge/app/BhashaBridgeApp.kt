package com.bhashabridge.app

import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Process
import android.os.SystemClock
import com.bhashabridge.app.bench.Metrics
import com.bhashabridge.app.mt.MtEngine
import com.bhashabridge.app.speech.VoskModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model

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

    /**
     * Two locks, and the split is the point.
     *
     * Every method here used to be `@Synchronized`, i.e. all of them on this object's single
     * monitor — including [translator], which **builds an engine inside it**: two dictionary parses
     * and three ONNX sessions, ~10 s on the SM-M315F. Any other thread touching this class blocked
     * for that whole span, and one of those threads is the main thread: `startRecording` runs
     * `withResources` on the main dispatcher, so tapping the microphone while the engine loaded
     * parked the UI thread on a ten-second lock. That is an ANR, not a stall.
     *
     * So the borrow counter — read and written constantly, held for nanoseconds — gets its own
     * monitor, and the engine map keeps the slow one.
     *
     * **Lock order is [borrowLock] then [engineLock], never the reverse.** [endUse] and
     * [onTrimMemory] take the counter first and the map second; [translator] takes only the map (via
     * [loadLocks]). Nothing acquires the counter while holding the map, so the cycle that would
     * deadlock cannot form.
     */
    private val borrowLock = Any()

    /** Guards [engines]. Held briefly — never across an engine construction (see [loadLocks]). */
    private val engineLock = Any()

    /**
     * One lock per direction, held for the length of that direction's construction.
     *
     * This is what keeps "only one thread builds a given engine" true without holding [engineLock]
     * for ten seconds. Two threads racing on the *same* direction serialise here and the loser sees
     * the winner's engine on the second look-up; two threads on *different* directions build
     * concurrently, which is harmless — they share nothing but the process-wide `OrtEnvironment`.
     */
    private val loadLocks: Map<Direction, Any> = Direction.entries.associateWith { Any() }

    private val engines = HashMap<Direction, MtEngine>()

    /**
     * How many callers are *inside* a borrowed native resource right now — a translation running in
     * ONNX Runtime, or a recording session holding a `Recognizer` bound to a Vosk model.
     *
     * Ownership alone is not enough to release safely. [translator] and [onTrimMemory] are both
     * synchronised, so *acquiring* an engine cannot race a release — but a call already inside
     * `MtEngine.translate()` holds no lock, and `AudioCapture.stop()` only sets a flag, so its loop
     * and final flush outlive the stop by however long the current read takes. Closing a session or
     * a model in either window frees native memory a live caller is still using: a process crash,
     * not an exception. This counter is the missing half — one owner, one trigger, and a defined
     * window in which the trigger may fire.
     */
    private var borrowers = 0

    /** True when a trim arrived while resources were borrowed; the last [endUse] does the release. */
    private var releaseWhenIdle = false

    /**
     * Set when a direction swap happened while resources were borrowed: the direction to **keep**,
     * so the last [endUse] evicts the others. Null when there is nothing deferred.
     */
    private var evictWhenIdle: Direction? = null

    /**
     * The Vosk acoustic models, owned here for the same reason the MT engines are: large native
     * allocations that must survive a rotation and must have exactly one release trigger. Loading
     * is lazy inside [VoskModels] too, so a session that never uses the mic never pays for it.
     */
    private val speechModelsLazy = lazy { VoskModels(this) }

    /**
     * `@PublishedApi internal`, not public, and that is the fix rather than a style choice.
     *
     * Both speech paths used to fetch a `Model` here and *then* open a borrow around the code that
     * used it. A `TRIM_MEMORY_BACKGROUND` in between saw `borrowers == 0`, called
     * `VoskModels.release()`, and freed the model — after which `Recognizer(model, …)` ran Vosk JNI
     * against a freed pointer. A SIGSEGV, not a catchable exception, and unattributable because the
     * stack is inside `libvosk.so`. The window is widest on the file-import path, which resumes from
     * the system document picker with a trim genuinely in flight.
     *
     * Callers now go through [withSpeechModel], which cannot express the mistake: the handle only
     * exists inside the pin.
     */
    @PublishedApi
    internal val speechModels: VoskModels get() = speechModelsLazy.value

    /**
     * Runs [block] with [direction]'s acoustic model, pinned for the whole call — including the
     * model load itself, which is seconds on first use.
     *
     * The load being inside the borrow means a trim arriving during a cold Vosk load is deferred
     * rather than executed. That is the same rule the MT path has always lived under.
     *
     * The load is dispatched to [Dispatchers.IO] here rather than left to the caller, because the
     * callers are `viewModelScope` coroutines on the main dispatcher and "pin first" must not be
     * bought by moving an asset unpack plus a native model load onto the UI thread.
     */
    suspend inline fun <T> withSpeechModel(
        direction: Direction,
        crossinline block: suspend (Model) -> T,
    ): T = withResources {
        val model = withContext(Dispatchers.IO) { speechModels.model(direction) }
        block(model)
    }

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
    fun translator(direction: Direction): MtEngine {
        // Fast path: resident engine, one uncontended lock acquisition, no chance of waiting behind
        // a construction. This is the path every translation after the first takes.
        synchronized(engineLock) { engines[direction] }?.let { return it }

        synchronized(loadLocks.getValue(direction)) {
            // Re-check: another thread may have built it while this one waited for the load lock.
            synchronized(engineLock) { engines[direction] }?.let { return it }

            logDebug(LogTag.APP) { "Loading MT engine: $direction" }
            // Phase 11A: one measured run around the whole construction. The stage marks inside
            // Tokenizer and OnnxModels attribute the time to tokenizer / verify / extract / session,
            // so the startup cost is broken down rather than reported as a single number.
            Metrics.begin("engine_init")
            val engine = MtEngine(this, direction)
            Metrics.counter("direction_en_hi", if (direction == Direction.EN_TO_HI) 1 else 0)
            Metrics.end()

            synchronized(engineLock) { engines[direction] = engine }
            evictOtherDirections(keep = direction)
            return engine
        }
    }

    /**
     * Keeps at most one MT engine resident, releasing the direction the user just left.
     *
     * `engines.getOrPut` held every direction ever opened, and the only eviction was
     * [onTrimMemory] — which fires when the process goes to background, not when the user taps
     * swap. So one tap on the swap button left **both** engines resident: six ONNX sessions and two
     * KV-cache runtimes, against the ~605-620 MB PSS one direction costs on the SM-M315F
     * (`ORT_TUNING.md`). ~1.2 GB in the foreground is a low-memory-killer candidate on the 4 GB
     * devices in the cross-device database, and it buys nothing: the UI translates in one direction
     * at a time, and `TranslateViewModel` cancels the outstanding translation before swapping.
     *
     * The cost of being wrong is a reload (~10 s) if the user swaps back and forth. That is the
     * right trade against being killed, and it is the same trade [onTrimMemory] already makes.
     *
     * Deferred exactly like a trim when anything is borrowed: a speech session pinned on the other
     * direction must not have its sessions closed underneath it. [endUse] runs whatever was
     * deferred.
     */
    private fun evictOtherDirections(keep: Direction) {
        val doomed = synchronized(borrowLock) {
            if (borrowers > 0) {
                evictWhenIdle = keep
                emptyList()
            } else {
                synchronized(engineLock) { takeOtherDirections(keep) }
            }
        }
        if (doomed.isEmpty()) return
        logDebug(LogTag.APP) { "Evicting ${doomed.size} engine(s) — $keep is now the active direction" }
        doomed.forEach { it.release() }
    }

    /** Removes and returns every engine except [keep]'s. Caller holds [engineLock]. */
    private fun takeOtherDirections(keep: Direction): List<MtEngine> {
        val victims = engines.filterKeys { it != keep }
        victims.keys.forEach { engines.remove(it) }
        return victims.values.toList()
    }

    /**
     * Runs [block] with the native resources pinned: they cannot be released underneath it, and a
     * trim that arrives while it runs is deferred to whichever borrower finishes last.
     *
     * Every path that *uses* (not merely fetches) an engine or a Vosk model goes through here.
     */
    // Inline so callers can suspend inside [block] — the recording session collects a Flow for the
    // whole span it needs the model pinned for, which a non-inline lambda could not express.
    inline fun <T> withResources(block: () -> T): T {
        beginUse()
        try {
            return block()
        } finally {
            endUse()
        }
    }

    @PublishedApi
    internal fun beginUse() {
        synchronized(borrowLock) { borrowers++ }
    }

    @PublishedApi
    internal fun endUse() {
        // Both deferred actions are decided under the counter lock and performed outside it. A full
        // release supersedes an eviction — it frees the kept engine too, so running both would just
        // close an already-empty map.
        var release = false
        var doomed: List<MtEngine> = emptyList()
        synchronized(borrowLock) {
            borrowers--
            if (borrowers > 0) return
            if (releaseWhenIdle) {
                releaseWhenIdle = false
                evictWhenIdle = null
                release = true
            } else evictWhenIdle?.let { keep ->
                evictWhenIdle = null
                doomed = synchronized(engineLock) { takeOtherDirections(keep) }
            }
        }
        if (release) {
            logDebug(LogTag.APP) { "Last borrower finished — running the deferred release" }
            releaseAll()
        }
        if (doomed.isNotEmpty()) {
            logDebug(LogTag.APP) { "Last borrower finished — running the deferred eviction" }
            doomed.forEach { it.release() }
        }
    }

    /**
     * The one release trigger for every process-lifetime native resource — ONNX sessions and Vosk
     * models alike. R4.5: `release()` and its call site land in the same commit, which is the
     * structural guard against the v3.4.1 leak where teardown existed but was never called.
     *
     * **The level is TRIM_MEMORY_BACKGROUND, and that is load-bearing.** This gate was
     * TRIM_MEMORY_COMPLETE, which the platform stopped delivering to apps targeting API 34 and
     * above ("Apps are not notified of this level since API level 34" — ComponentCallbacks2). With
     * `targetSdk = 36` the branch below could never be taken, so on every Android 14+ device the
     * ~600 MB of models was held until the process died and this method was decoration. That is the
     * v3.4.1 L2 lesson in its third disguise: not a missing call site this time, but a live one the
     * platform quietly stopped calling. Of the seven trim levels only BACKGROUND and UI_HIDDEN
     * survive on 34+; BACKGROUND is the one that means what the old comment claimed — the process
     * is on the background LRU list and a kill candidate. UI_HIDDEN would fire on every home-press
     * and buy back a ~27 s reload for a user who is coming straight back.
     *
     * Releasing is safe here only because [borrowers] says nothing is mid-use; a trim during a
     * translation or a recording session is deferred rather than skipped, so the memory is still
     * returned, just at the moment it stops being dangerous.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) return
        val deferred = synchronized(borrowLock) {
            if (borrowers > 0) {
                releaseWhenIdle = true
                true
            } else false
        }
        if (deferred) {
            logDebug(LogTag.APP) { "Trim level $level — borrower(s) active; deferring release" }
            return
        }
        releaseAll()
    }

    /**
     * Closes everything held. Callers must have checked [borrowers] under [borrowLock] first.
     *
     * The map is emptied under [engineLock] and the native `release()` calls happen *outside* it, so
     * a teardown never holds the lock a `translator()` fast path needs. Nothing else can reach the
     * engines by then — they are already out of the map.
     */
    private fun releaseAll() {
        val doomed = synchronized(engineLock) {
            val all = engines.values.toList()
            engines.clear()
            all
        }
        if (doomed.isNotEmpty()) {
            logDebug(LogTag.APP) { "Releasing ${doomed.size} engine(s)" }
            doomed.forEach { it.release() }
        }
        // Guarded, not `speechModels.release()`: touching the property would *construct* the
        // models we are trying to avoid holding.
        if (speechModelsLazy.isInitialized()) {
            logDebug(LogTag.APP) { "Releasing speech models" }
            speechModelsLazy.value.release()
        }
    }
}
