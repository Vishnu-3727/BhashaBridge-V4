package com.bhashabridge.app.bench

import org.junit.Assert.assertEquals
import org.junit.Test

/** Locks the percentile/throughput maths the whole benchmark framework reports through. */
class StatsTest {

    @Test fun descriptiveStats() {
        val s = Stats.of(listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100))
        assertEquals(10, s.n)
        assertEquals(10.0, s.min, 0.0)
        assertEquals(100.0, s.max, 0.0)
        assertEquals(55.0, s.mean, 0.0)
        assertEquals(60.0, s.median, 0.0)   // nearest-rank round(0.50*9)=5 -> sorted[5]
        assertEquals(100.0, s.p95, 0.0)     // round(0.95*9)=9 -> sorted[9]
        assertEquals(100.0, s.p99, 0.0)
    }

    @Test fun emptyIsZeroed() {
        assertEquals(0, Stats.of(emptyList<Int>()).n)
        assertEquals(0.0, Stats.of(emptyList<Int>()).median, 0.0)
    }

    @Test fun tokensPerSecAveragesPerCallRates() {
        // 10 tokens in 1000 ms = 10/s; 10 tokens in 2000 ms = 5/s; mean = 7.5.
        assertEquals(7.5, Stats.tokensPerSec(listOf(10, 10), listOf(1000, 2000)), 1e-9)
    }

    @Test fun tokensPerSecSkipsZeroLatency() {
        assertEquals(10.0, Stats.tokensPerSec(listOf(10, 10), listOf(1000, 0)), 1e-9)
    }
}
