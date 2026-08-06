package com.bhashabridge.app.mt

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.bench.Stats
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer

/**
 * Prices the per-token logits read (OPTIMIZATION_SUMMARY §3.22, queue item Q2).
 *
 * `13007e3` halved the copying — two full-width copies per generated token became one — and shipped
 * with **no** latency claim, because no device was attached. This is that measurement.
 *
 * It times the reader in isolation against a synthetic `[1, 1, vocab]` tensor rather than end to end
 * through a translation. That is deliberate: end-to-end, this cost is a few percent buried under
 * ~41 ms/token of ORT kernel time and thermal drift, and the question here is a narrow one — what
 * does one row read cost, and how much did the change remove. A synthetic tensor exercises the same
 * `getFloatBuffer()` native→heap copy the real one does.
 *
 * The old reader is reproduced here rather than kept in production code, so the comparison is
 * against what actually shipped before, not against a description of it.
 *
 * Results land under `BB.LogitsBench`. Run:
 * `adb shell am instrument -w -e class com.bhashabridge.app.mt.LogitsReadBenchmarkTest \
 *   com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class LogitsReadBenchmarkTest {

    /** What `lastLogitsRow` looked like before `13007e3`: `value` boxes, then `copyOf` duplicates. */
    @Suppress("UNCHECKED_CAST")
    private fun boxedRead(tensor: OnnxTensor): FloatArray =
        (tensor.value as Array<Array<FloatArray>>)[0].last().copyOf()

    @Test
    fun bufferReadVersusTheBoxedReadItReplaced() {
        val env = OrtEnvironment.getEnvironment()

        for (vocab in intArrayOf(EN_HI_VOCAB, HI_EN_VOCAB)) {
            val data = FloatArray(vocab) { it * 0.001f }
            val shape = longArrayOf(1, 1, vocab.toLong())

            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape).use { tensor ->
                // Parity first: a faster reader that reads something else is not a result.
                assertArrayEquals(
                    "the buffer read must return exactly what the boxed read returned",
                    boxedRead(tensor), lastLogitsRow(tensor), 0f,
                )

                repeat(WARMUP) { boxedRead(tensor); lastLogitsRow(tensor) }

                val boxed = ArrayList<Long>(ITERATIONS)
                val buffered = ArrayList<Long>(ITERATIONS)
                // Interleaved, not one batch after the other, so a thermal ramp or a GC pause lands
                // on both arms instead of on whichever ran second.
                repeat(ITERATIONS) {
                    var t = System.nanoTime()
                    boxedRead(tensor)
                    boxed += System.nanoTime() - t

                    t = System.nanoTime()
                    lastLogitsRow(tensor)
                    buffered += System.nanoTime() - t
                }

                val b = Stats.of(boxed.map { it / 1000.0 })       // µs
                val f = Stats.of(buffered.map { it / 1000.0 })
                Log.i(TAG, "REPORT vocab=$vocab n=$ITERATIONS")
                Log.i(TAG, "REPORT   boxed_us   median=${b.median} p95=${b.p95} stdev=${b.stdev}")
                Log.i(TAG, "REPORT   buffer_us  median=${f.median} p95=${f.p95} stdev=${f.stdev}")
                Log.i(
                    TAG,
                    "REPORT   saved_us_per_token=${"%.1f".format(b.median - f.median)} " +
                        "speedup=${"%.2f".format(if (f.median > 0) b.median / f.median else 0.0)}x",
                )
                // At 12 generated tokens — the project's long benchmark sentence.
                Log.i(TAG, "REPORT   saved_ms_per_12_token_translation=${"%.2f".format((b.median - f.median) * 12 / 1000.0)}")
            }
        }
    }

    private companion object {
        const val TAG = "BB.LogitsBench"
        const val EN_HI_VOCAB = 122_672
        const val HI_EN_VOCAB = 32_296
        const val WARMUP = 20
        const val ITERATIONS = 200
    }
}
