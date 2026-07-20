package com.bhashabridge.app.bench

import android.util.Log
import com.bhashabridge.app.BuildConfig
import com.bhashabridge.app.LogTag
import java.util.Locale

/**
 * Purpose:  Records timed stages and counters for one operation, emitted as one structured line.
 * Owns:     One [Run] per thread, created by [begin] and released by [end].
 * Lifetime: Process
 * Thread:   A run is thread-confined — see the ThreadLocal note on [active].
 *
 * Knows nothing about translation, speech, ONNX, or Android. Every label is caller-supplied.
 *
 * Release builds contain none of this. The four entry points are inline and guarded by
 * `BuildConfig.DEBUG`, which is a compile-time `false` in release, so the call, its arguments, and
 * any string built inside are eliminated at the call site — the object is never class-loaded. That
 * is a language guarantee rather than an R8 side effect, which matters because the release build
 * currently runs with `optimization { enable = false }`.
 *
 * DEPENDENCY_RULES.md D4 (amended) permits a subsystem to import this one type, and nothing else
 * from `bench/`. The test the amendment states: in a release build, no instruction from `bench/`
 * executes.
 */
object Metrics {

    // A run belongs to the thread that started it. No locks, no contention, and two subsystems
    // measuring concurrently cannot interleave into each other's stages (R6.3).
    //
    // Known limitation, detected rather than prevented: a run that spans a coroutine dispatcher
    // hop will not find its state on the new thread. stage()/end() log a warning instead of
    // failing silently. Propagating through coroutine context is real complexity that no current
    // caller needs; a caller that does need it is the trigger to build it.
    private val active = ThreadLocal<Run?>()

    /** Starts a run on this thread. Discards any unfinished run — see [beginNow]. */
    inline fun begin(run: String) {
        if (BuildConfig.DEBUG) beginNow(run)
    }

    /** Closes the open stage and opens [name]. */
    inline fun stage(name: String) {
        if (BuildConfig.DEBUG) stageNow(name)
    }

    /** Adds [value] to the named counter, creating it at zero on first use. */
    inline fun counter(name: String, value: Long) {
        if (BuildConfig.DEBUG) counterNow(name, value)
    }

    /** Closes the final stage and emits the run. */
    inline fun end() {
        if (BuildConfig.DEBUG) endNow()
    }

    /**
     * An unfinished run on this thread means a previous operation threw between [begin] and [end].
     * Discarding it here makes that self-healing, which is why there is no `cancel()` in the API —
     * the failure path is "never call end()", and the next run cleans up after it.
     */
    @PublishedApi
    internal fun beginNow(run: String) {
        val stale = active.get()
        if (stale != null) {
            Log.w(LogTag.BENCH, "Run '${stale.label}' never ended — discarded by '$run'")
        }
        active.set(Run(run, System.nanoTime()))
    }

    @PublishedApi
    internal fun stageNow(name: String) {
        val run = active.get()
        if (run == null) {
            Log.w(LogTag.BENCH, "stage('$name') with no active run on this thread")
            return
        }
        run.closeStage(name, System.nanoTime())
    }

    @PublishedApi
    internal fun counterNow(name: String, value: Long) {
        val run = active.get()
        if (run == null) {
            Log.w(LogTag.BENCH, "counter('$name') with no active run on this thread")
            return
        }
        run.addCounter(name, value)
    }

    @PublishedApi
    internal fun endNow() {
        val run = active.get()
        if (run == null) {
            Log.w(LogTag.BENCH, "end() with no active run on this thread")
            return
        }
        active.set(null)
        Log.d(LogTag.BENCH, run.toJson(System.nanoTime()))
    }

    /**
     * One operation's measurements. Pure — no Android imports reachable from here, so it is
     * directly unit-testable on the JVM. That matters more than it looks: a defect in this class
     * silently corrupts every performance number V4 will ever claim.
     */
    internal class Run(val label: String, private val startNanos: Long) {

        // Insertion-ordered so emitted stages read in execution order.
        private val stages = LinkedHashMap<String, Long>()
        private val counters = LinkedHashMap<String, Long>()
        private var lastMark = startNanos

        /** Attributes elapsed time since the previous mark to [name]. Repeats accumulate. */
        fun closeStage(name: String, now: Long) {
            stages[name] = (stages[name] ?: 0L) + (now - lastMark)
            lastMark = now
        }

        fun addCounter(name: String, value: Long) {
            counters[name] = (counters[name] ?: 0L) + value
        }

        fun toJson(endNanos: Long): String {
            val sb = StringBuilder(128)
            sb.append("{\"run\":\"").append(escape(label)).append('"')
            sb.append(",\"total_ms\":").append(ms(endNanos - startNanos))
            appendMap(sb, "stages", stages) { ms(it) }
            appendMap(sb, "counters", counters) { it.toString() }
            return sb.append('}').toString()
        }

        private inline fun appendMap(
            sb: StringBuilder,
            key: String,
            map: Map<String, Long>,
            value: (Long) -> String,
        ) {
            if (map.isEmpty()) return
            sb.append(",\"").append(key).append("\":{")
            var first = true
            for ((k, v) in map) {
                if (!first) sb.append(',')
                sb.append('"').append(escape(k)).append("\":").append(value(v))
                first = false
            }
            sb.append('}')
        }

        // Locale.ROOT, not the default: a Hindi or German device locale formats decimals with a
        // comma, which produces JSON the bench harness cannot parse. Silent data corruption.
        private fun ms(nanos: Long): String =
            String.format(Locale.ROOT, "%.3f", nanos / 1_000_000.0)

        // Labels are developer-supplied constants today, so this is cheap insurance rather than a
        // full JSON encoder — but one stray quote emitting an unparseable line would be found by
        // whoever is analysing a benchmark run, not by whoever wrote the label.
        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("\"", "\\\"")
    }
}
