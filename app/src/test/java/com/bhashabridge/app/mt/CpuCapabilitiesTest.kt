package com.bhashabridge.app.mt

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Locks [CpuCapabilities.perfEffSplit] — the perf/eff cluster classifier. The regression this pins is
 * the tri-cluster bug: a Snapdragon 8 Gen 1 must count its three Cortex-A710 mid cores as performance,
 * not efficiency, while 2-cluster big.LITTLE parts must split exactly as they did before the fix.
 * Frequencies are cpufreq `cpuinfo_max_freq` (kHz, the static policy max — throttle-independent).
 */
class CpuCapabilitiesTest {

    private fun split(vararg maxFreqKhz: Long) = CpuCapabilities.perfEffSplit(maxFreqKhz.toList())

    @Test fun triClusterCountsMidCoresAsPerformance() {
        // SM-S908E (SD8 Gen1): A510 cpu0-3 @1785, A710 cpu4-6 @2496, X2 cpu7 @2995.
        val (perf, eff) = split(1785000, 1785000, 1785000, 1785000, 2496000, 2496000, 2496000, 2995000)
        assertEquals("A710 mids + X2 are performance", listOf(4, 5, 6, 7), perf)
        assertEquals("only the A510 littles are efficiency", listOf(0, 1, 2, 3), eff)
    }

    @Test fun bigLittle4plus4Unchanged() {
        // Dimensity 7300: A55 cpu0-3 @2000, A78 cpu4-7 @2500.
        val (perf, eff) = split(2000000, 2000000, 2000000, 2000000, 2500000, 2500000, 2500000, 2500000)
        assertEquals(listOf(4, 5, 6, 7), perf)
        assertEquals(listOf(0, 1, 2, 3), eff)
    }

    @Test fun bigLittle2plus6Unchanged() {
        // Dimensity 930: A55 cpu0-5 @2000, A78 cpu6-7 @2200.
        val (perf, eff) = split(2000000, 2000000, 2000000, 2000000, 2000000, 2000000, 2200000, 2200000)
        assertEquals(listOf(6, 7), perf)
        assertEquals(listOf(0, 1, 2, 3, 4, 5), eff)
    }

    @Test fun singleFrequencyTierIsAllPerformance() {
        val (perf, eff) = split(1800000, 1800000, 1800000, 1800000)
        assertEquals(listOf(0, 1, 2, 3), perf)
        assertEquals(emptyList<Int>(), eff)
    }

    @Test fun unreadableCpufreqDegradesToAllPerformance() {
        val (perf, eff) = split(0, 0, 0, 0)
        assertEquals(listOf(0, 1, 2, 3), perf)
        assertEquals(emptyList<Int>(), eff)
    }

    @Test fun unreadableCoreNeverJoinsPerformancePool() {
        // One core's freq unreadable (0) alongside a real big.LITTLE split: the 0 core is efficiency.
        val (perf, eff) = split(0, 2000000, 2500000, 2500000)
        assertEquals(listOf(2, 3), perf)
        assertEquals(listOf(0, 1), eff)
    }
}
