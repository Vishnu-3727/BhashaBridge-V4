package com.bhashabridge.app.mt

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.bench.SystemStats
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 5 unified benchmark runner. One test that exercises every optimization in the stack and writes
 * a single structured JSON report — the reproducible, exportable record the offline exporter
 * (model_pipeline/bench_report.py) turns into CSV / Markdown / a regression summary.
 *
 * It measures, in order: startup (cold/warm/hot builds with tokenizer / model-load / engine-init
 * splits), on-disk cache sizes, first-translation latency (TTFT at translation granularity), sustained
 * translation (30 counterbalanced iterations → median/p95/p99/stdev/tokens-per-sec), and a full
 * memory/CPU/thermal/battery snapshot before and after via [SystemStats].
 *
 * Pure measurement. It only calls the same public constructors and translate() the app uses; it never
 * touches inference, the cache, the execution policy, or ORT. All maths goes through [Stats] so the
 * on-device numbers match the offline ones.
 */
@RunWith(AndroidJUnit4::class)
class BenchmarkSuiteTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val direction = Direction.EN_TO_HI

    private val short = "Water."
    private val long = "The weather is very nice today and I want to go outside."
    private val sentences = listOf(short, long)
    private val warmup = 5
    private val iterations = 30

    @Test
    fun runSuite() {
        val caps = CpuCapabilities.detect()
        val policy = ExecutionPolicy.current
        val before = SystemStats.capture(context, "before")

        val startup = measureStartup()          // cold / warm / hot, also leaves the .ort cache warm
        val cache = cacheBytes()
        val engine = MtEngine(context, direction)
        val counter = Tokenizer.load(context, direction) // for target-side token counting only
        val inference = try {
            measureInference(engine) { counter.encode(it).size.toLong() }
        } finally {
            engine.release()
        }

        val after = SystemStats.capture(context, "after")
        val report = buildReport(caps, policy, startup, cache, inference, before, after)

        val file = writeReport(report)
        Log.i(TAG, "REPORT_FILE $file")
        report.chunked(3000).forEachIndexed { i, c -> Log.i(TAG, "REPORT_JSON $i $c") }

        assertTrue("no sustained samples", inference.sustainedMedian > 0.0)
        assertTrue("report not written", file != null && File(file).exists())
    }

    // ---- startup ----------------------------------------------------------------------------------

    private class Phase(val tokenizerMs: Long, val modelLoadMs: Long) {
        val engineInitMs get() = tokenizerMs + modelLoadMs
    }

    /** Cold (cache cleared → bake), warm (mmap the .ort), hot (second warm build, pages cached). */
    private fun measureStartup(): Map<String, Phase> {
        clearCache()
        val cold = onePhase()
        val warm = onePhase()
        val hot = onePhase()
        return linkedMapOf("cold" to cold, "warm" to warm, "hot" to hot)
    }

    private fun onePhase(): Phase {
        val tok = time { Tokenizer.load(context, direction) }
        val models = timeValue { OnnxModels(context, direction) }
        models.value.release()
        return Phase(tok, models.ms)
    }

    // ---- inference --------------------------------------------------------------------------------

    private class Inference(
        val firstTranslationMs: Long,
        val sustained: Stats,
        val perSentence: Map<String, Stats>,
        val tokensPerSec: Double,
        val sampleOutput: String,
    ) {
        val sustainedMedian get() = sustained.median
    }

    private fun measureInference(engine: MtEngine, tokenCount: (String) -> Long): Inference {
        repeat(warmup) { sentences.forEach { engine.translate(it) } }

        val firstStart = System.nanoTime()
        val firstOut = engine.translate(long)
        val firstMs = (System.nanoTime() - firstStart) / 1_000_000

        val perSentence = sentences.associateWith { ArrayList<Long>(iterations) }
        val allLatency = ArrayList<Long>(iterations * sentences.size)
        val allTokens = ArrayList<Long>(iterations * sentences.size)
        var lastOut = firstOut
        for (i in 0 until iterations) {
            // Counterbalance ordering: alternate which sentence leads each round.
            val order = if (i % 2 == 0) sentences else sentences.asReversed()
            for (s in order) {
                val t = System.nanoTime()
                val out = engine.translate(s)
                val ms = (System.nanoTime() - t) / 1_000_000
                perSentence.getValue(s) += ms
                allLatency += ms
                allTokens += tokenCount(out)
                lastOut = out
            }
        }
        return Inference(
            firstTranslationMs = firstMs,
            sustained = Stats.of(allLatency),
            perSentence = perSentence.mapValues { Stats.of(it.value) },
            tokensPerSec = Stats.tokensPerSec(allTokens, allLatency),
            sampleOutput = lastOut,
        )
    }

    // ---- report assembly --------------------------------------------------------------------------

    private fun buildReport(
        caps: CpuCapabilities,
        policy: OrtTuning,
        startup: Map<String, Phase>,
        cache: Pair<Long, Long>,
        inf: Inference,
        before: SystemStats,
        after: SystemStats,
    ): String = buildString {
        append("{\"schema\":\"bb-bench/1\"")
        append(",\"timestampMs\":").append(System.currentTimeMillis())
        append(",\"device\":{")
        append("\"model\":\"").append(esc(Build.MODEL)).append("\"")
        append(",\"androidSdk\":").append(Build.VERSION.SDK_INT)
        append(",\"abi\":\"").append(esc(Build.SUPPORTED_ABIS.firstOrNull() ?: "")).append("\"")
        append(",\"cpu\":\"").append(esc(caps.describe())).append("\"")
        append(",\"policy\":\"").append(esc("${policy.name} intra=${policy.intraThreads} arena=${policy.cpuArena} affinity=${policy.intraOpAffinities ?: "OFF"}")).append("\"")
        append("}")
        append(",\"startup\":{")
        startup.entries.forEachIndexed { i, (k, p) ->
            if (i > 0) append(',')
            append("\"$k\":{\"tokenizerMs\":${p.tokenizerMs},\"modelLoadMs\":${p.modelLoadMs},\"engineInitMs\":${p.engineInitMs}}")
        }
        append("}")
        append(",\"cacheBytes\":{\"sourceOnnx\":${cache.first},\"ortCache\":${cache.second}}")
        append(",\"inference\":{")
        append("\"firstTranslationMs\":${inf.firstTranslationMs}")
        append(",\"iterations\":$iterations,\"counterbalanced\":true")
        append(",\"tokensPerSec\":${round3(inf.tokensPerSec)}")
        append(",\"sustained\":").append(inf.sustained.toJson())
        append(",\"perSentence\":{")
        inf.perSentence.entries.forEachIndexed { i, (s, st) ->
            if (i > 0) append(',')
            append("\"").append(esc(s)).append("\":").append(st.toJson())
        }
        append("}")
        append(",\"sampleOutput\":\"").append(esc(inf.sampleOutput)).append("\"")
        append("}")
        append(",\"systemBefore\":").append(before.toJson())
        append(",\"systemAfter\":").append(after.toJson())
        append("}")
    }

    private fun writeReport(json: String): String? = runCatching {
        val dir = File(context.getExternalFilesDir(null), "bench").apply { mkdirs() }
        val f = File(dir, "bench_report_${System.currentTimeMillis()}.json")
        f.writeText(json)
        f.absolutePath
    }.getOrNull()

    // ---- helpers ----------------------------------------------------------------------------------

    private fun cacheBytes(): Pair<Long, Long> {
        val files = context.filesDir.listFiles() ?: emptyArray()
        // Q21 (§3.47): the baked cache is `.opt.onnx` plus the shared `.bin` blob it points at, so
        // the blob counts as cache -- it is what the graphs need on disk to load at all. A plain
        // `.onnx` match would now scoop up the baked graphs as if they were source copies.
        val source = files.filter { it.name.endsWith(".onnx") && !it.name.endsWith(".opt.onnx") }
            .sumOf { it.length() }
        val cache = files.filter { it.name.endsWith(".opt.onnx") || it.name.endsWith(".bin") }
            .sumOf { it.length() }
        return source to cache
    }

    private fun clearCache() {
        (context.filesDir.listFiles() ?: emptyArray()).forEach {
            if (it.name.endsWith(".ort") || it.name.endsWith(".ort.stamp") ||
                it.name.endsWith(".opt.stamp") || it.name.endsWith(".onnx") || it.name.endsWith(".bin")
            ) it.delete()
        }
    }

    private class Timed<T>(val value: T, val ms: Long)
    private inline fun <T> timeValue(block: () -> T): Timed<T> {
        val t = System.nanoTime(); val v = block(); return Timed(v, (System.nanoTime() - t) / 1_000_000)
    }
    private inline fun time(block: () -> Unit): Long {
        val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000
    }
    private fun round3(v: Double) = (v * 1000).toLong() / 1000.0
    private fun esc(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")

    private companion object {
        const val TAG = "BB.Suite"
    }
}
