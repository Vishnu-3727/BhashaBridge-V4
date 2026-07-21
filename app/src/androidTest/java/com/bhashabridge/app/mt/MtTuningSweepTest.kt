package com.bhashabridge.app.mt

import ai.onnxruntime.OrtSession.SessionOptions.OptLevel
import android.os.Debug
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 7 ORT-tuning sweep. One build, many [OrtTuning] configs — each changes exactly one knob off
 * the baseline (ORT defaults), so every optimisation gets independent evidence. A fresh [MtEngine]
 * (reloading the three sessions with that config's options) is built per config and released before
 * the next, so configs never share native state. `baseline` runs first and `baseline_end` last to
 * bound thermal drift over the sweep.
 *
 * Same harness contract as [MtBenchmarkTest]: `Metrics` logs one JSON line per translation; the
 * `BB_BENCH` markers (now including `CONFIG name=`) let `bench_tune_parse.py` segment per config.
 * Only session options vary — decoder, tokenizer, cache, and weights are identical throughout, and
 * every config must produce the same translation (parity check in the parser).
 */
@RunWith(AndroidJUnit4::class)
class MtTuningSweepTest {

    private val sentences = listOf(
        "Hello, how are you?",
        "The weather is very nice today and I want to go outside.",
    )
    private val warmup = 2
    private val runs = 30

    private val configs = listOf(
        OrtTuning("baseline"),
        OrtTuning("opt_none", optLevel = OptLevel.NO_OPT),
        OrtTuning("opt_extended", optLevel = OptLevel.EXTENDED_OPT),
        OrtTuning("intra1", intraThreads = 1),
        OrtTuning("intra2", intraThreads = 2),
        OrtTuning("intra4", intraThreads = 4),
        OrtTuning("intra8", intraThreads = 8),
        OrtTuning("parallel", parallel = true),
        OrtTuning("parallel_inter2", parallel = true, interThreads = 2),
        OrtTuning("arena_off", cpuArena = false),
        OrtTuning("mempattern_off", memPattern = false),
        OrtTuning("baseline_end"),
    )

    @Test
    fun sweep() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        for (cfg in configs) {
            Log.i(TAG, "CONFIG name=${cfg.name}")
            val engine = MtEngine(ctx, Direction.EN_TO_HI, GreedyDecoder(), cfg)
            try {
                repeat(warmup) { sentences.forEach { engine.translate(it) } }
                Log.i(TAG, "WARMUP_DONE ${cfg.name}")
                sentences.forEachIndexed { i, s ->
                    Log.i(TAG, "BENCH_SENTENCE i=$i n=$runs text=\"$s\"")
                    var out = ""
                    repeat(runs) { out = engine.translate(s) }
                    Log.i(TAG, "BENCH_OUTPUT i=$i out=\"$out\"")
                }
                val mi = Debug.MemoryInfo()
                Debug.getMemoryInfo(mi)
                Log.i(TAG, "BENCH_MEM totalPss=${mi.totalPss} nativePss=${mi.nativePss} dalvikPss=${mi.dalvikPss}")
            } finally {
                engine.release()
            }
        }
    }

    private companion object {
        const val TAG = "BB_BENCH"
    }
}
