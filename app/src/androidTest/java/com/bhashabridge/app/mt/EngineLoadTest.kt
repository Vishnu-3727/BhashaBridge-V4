package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Engine construction, split into its two halves, for queue items Q3 and Q4.
 *
 * `MtEngine` builds a `Tokenizer` and then an `OnnxModels`. The three ONNX graphs already load on
 * three threads (§3.12), but the two dictionary parses run **first, alone, on the calling thread** —
 * about a second of a ~10.5 s cold start doing nothing the session loads could not be doing at the
 * same time.
 *
 * One engine per process invocation, deliberately: a second build in the same process finds the
 * page cache warm and the ORT environment initialised, which is the confound that ate Q14's
 * load-time claim.
 *
 * Logged under `BB.Load`.
 */
@RunWith(AndroidJUnit4::class)
class EngineLoadTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    @Test
    fun engineConstructionCost() {
        val tokenizerStart = System.nanoTime()
        Tokenizer.load(app, Direction.EN_TO_HI)
        val tokenizerMs = (System.nanoTime() - tokenizerStart) / 1_000_000

        val totalStart = System.nanoTime()
        val engine = MtEngine(app, Direction.EN_TO_HI)
        val totalMs = (System.nanoTime() - totalStart) / 1_000_000

        // Sanity: the engine has to actually work, or a fast load means nothing.
        val out = engine.translate("Water.")
        Log.i(TAG, "REPORT tokenizer_alone_ms=$tokenizerMs engine_total_ms=$totalMs out=$out")
        engine.release()
    }

    /**
     * Which half is the critical path now that they run concurrently (Q3)?
     *
     * This decides whether Q4 — replacing the JSON vocabulary with a packed binary — is worth
     * building. Concurrency does not make work free, it hides the shorter task behind the longer
     * one: if the sessions dominate, making the parse faster saves nothing at all, and Q4 would be
     * effort spent on a number the user cannot observe.
     *
     * Each half is timed in its own process so neither warms the other's page cache.
     */
    @Test
    fun tokenizerOnly() {
        val start = System.nanoTime()
        Tokenizer.load(app, Direction.EN_TO_HI)
        Log.i(TAG, "REPORT half=tokenizer ms=${(System.nanoTime() - start) / 1_000_000}")
    }

    @Test
    fun sessionsOnly() {
        val start = System.nanoTime()
        val models = OnnxModels(app, Direction.EN_TO_HI, ExecutionPolicy.current)
        Log.i(TAG, "REPORT half=sessions ms=${(System.nanoTime() - start) / 1_000_000}")
        models.release()
    }

    /**
     * Attribution for the 2.6 s vocabulary load: I/O, or the parser running cold?
     *
     * Three loads in one process. The first pays page-cache misses **and** an interpreted parse
     * loop; by the third, both the file pages and the JIT-compiled parser are warm. A large drop
     * from first to third means the cost is the parse executing cold, not reading the files — which
     * decides whether Q4 should be a packed binary format (less work per byte) or something else
     * entirely.
     */
    @Test
    fun tokenizerThreeTimesInOneProcess() {
        repeat(3) { attempt ->
            val start = System.nanoTime()
            Tokenizer.load(app, Direction.EN_TO_HI)
            Log.i(TAG, "REPORT attempt=${attempt + 1} ms=${(System.nanoTime() - start) / 1_000_000}")
        }
    }

    private companion object {
        const val TAG = "BB.Load"
    }
}
