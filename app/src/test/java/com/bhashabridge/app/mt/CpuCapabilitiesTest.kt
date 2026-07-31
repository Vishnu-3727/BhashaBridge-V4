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

    @Test fun uniformCoreIpIsAllPerformanceDespiteTwoFrequencyTiers() {
        // SM-S948B (SD 8 Elite Gen 5): 8× Oryon, every core part 0x002 — 6 perf @3629, 2 prime @4742.
        // No little cluster exists, so the bottom tier must NOT become the efficiency pool.
        val freqs = listOf(
            3628800L, 3628800L, 3628800L, 3628800L, 3628800L, 3628800L, 4742400L, 4742400L,
        )
        val (perf, eff) = CpuCapabilities.perfEffSplit(freqs, List(8) { "0x002" })
        assertEquals("all 8 Oryon cores are performance", (0..7).toList(), perf)
        assertEquals("a uniform-IP CPU has no efficiency cluster", emptyList<Int>(), eff)
    }

    @Test fun heterogeneousPartsStillSplitByFrequency() {
        // Same tri-cluster part as the first test, now with its real CPU part ids supplied: the parts
        // differ, so the frequency rule still applies and the split is unchanged.
        val freqs = listOf(
            1785000L, 1785000L, 1785000L, 1785000L, 2496000L, 2496000L, 2496000L, 2995000L,
        )
        val parts = listOf("0xd46", "0xd46", "0xd46", "0xd46", "0xd47", "0xd47", "0xd47", "0xd4e")
        val (perf, eff) = CpuCapabilities.perfEffSplit(freqs, parts)
        assertEquals(listOf(4, 5, 6, 7), perf)
        assertEquals(listOf(0, 1, 2, 3), eff)
    }

    @Test fun partialCpuPartsFallBackToFrequencyRule() {
        // Kernel described only some cores: uniformity is unproven, so the frequency split must stand
        // rather than silently promoting a genuine little cluster to performance.
        val freqs = listOf(2000000L, 2000000L, 2000000L, 2000000L, 2500000L, 2500000L, 2500000L, 2500000L)
        val (perf, eff) = CpuCapabilities.perfEffSplit(freqs, listOf("0xd05", "0xd41"))
        assertEquals(listOf(4, 5, 6, 7), perf)
        assertEquals(listOf(0, 1, 2, 3), eff)
    }
}
