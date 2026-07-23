package com.bhashabridge.app.bench

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure descriptive statistics for a run of latency samples — the one place percentiles are computed,
 * replacing the copy that had drifted into each benchmark test. No Android imports, so it is unit
 * testable on the JVM. Percentiles use nearest-rank on the sorted samples (round(q*(n-1))), matching
 * the existing bench_parse.py so on-device and offline numbers agree.
 */
data class Stats(
    val n: Int,
    val min: Double,
    val max: Double,
    val mean: Double,
    val median: Double,
    val p95: Double,
    val p99: Double,
    val stdev: Double,
) {
    fun toJson(): String =
        """{"n":$n,"min":${r(min)},"max":${r(max)},"mean":${r(mean)},"median":${r(median)},""" +
            """"p95":${r(p95)},"p99":${r(p99)},"stdev":${r(stdev)}}"""

    private fun r(v: Double) = (v * 1000).roundToInt() / 1000.0

    companion object {
        val EMPTY = Stats(0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

        fun of(samples: List<Number>): Stats {
            if (samples.isEmpty()) return EMPTY
            val xs = samples.map { it.toDouble() }.sorted()
            val n = xs.size
            val mean = xs.average()
            val variance = if (n > 1) xs.sumOf { (it - mean) * (it - mean) } / n else 0.0
            return Stats(
                n = n,
                min = xs.first(),
                max = xs.last(),
                mean = mean,
                median = percentile(xs, 0.50),
                p95 = percentile(xs, 0.95),
                p99 = percentile(xs, 0.99),
                stdev = sqrt(variance),
            )
        }

        /** Nearest-rank percentile on an already-sorted list. [q] in [0,1]. */
        private fun percentile(sorted: List<Double>, q: Double): Double {
            if (sorted.isEmpty()) return 0.0
            val idx = (q * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)
            return sorted[idx]
        }

        /** Throughput from paired token counts and latencies (ms). Zero-latency samples are skipped. */
        fun tokensPerSec(tokens: List<Number>, latencyMs: List<Number>): Double {
            val rates = tokens.zip(latencyMs).mapNotNull { (t, ms) ->
                val d = ms.toDouble()
                if (d > 0.0) t.toDouble() / (d / 1000.0) else null
            }
            return if (rates.isEmpty()) 0.0 else rates.average()
        }
    }
}
