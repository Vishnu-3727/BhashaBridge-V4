package com.bhashabridge.app.smoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The presets are a table, and a table's failure mode is a typo that nobody notices until a report
 * is already published — a Heavy run that soaks for less time than Standard produces a plausible
 * number with a wrong label on it.
 */
class PresetTest {

    /** Declaration order is intensity order; every budget has to agree with it. */
    @Test
    fun budgetsRiseWithIntensity() {
        BenchRunner.Preset.entries.zipWithNext { lighter, heavier ->
            assertTrue(
                "${heavier.name} must run kernels at least as long as ${lighter.name}",
                heavier.syntheticMinMs > lighter.syntheticMinMs,
            )
            assertTrue(
                "${heavier.name} must do more MT iterations than ${lighter.name}",
                heavier.mtIterations > lighter.mtIterations,
            )
            assertTrue(
                "${heavier.name} must soak at least as long as ${lighter.name}",
                heavier.sustainedSeconds >= lighter.sustainedSeconds,
            )
            assertTrue(
                "${heavier.name}'s estimate must exceed ${lighter.name}'s",
                heavier.approxMinutes > lighter.approxMinutes,
            )
        }
    }

    /**
     * STANDARD is the configuration every published baseline was measured with — README.md's
     * SM-M315F block and `bench/results/`. Changing it silently invalidates those comparisons, so
     * it is pinned here rather than left to a code review to notice.
     */
    @Test
    fun standardMatchesThePublishedBaseline() {
        val s = BenchRunner.Preset.STANDARD
        assertEquals(SyntheticWorkload.DEFAULT_MIN_DURATION_MS, s.syntheticMinMs)
        assertEquals(20, s.mtIterations)
        assertEquals(60, s.sustainedSeconds)
    }

    /** LIGHT is triage: fast enough that skipping the soak is the point, not an oversight. */
    @Test
    fun lightSkipsTheSoak() {
        assertEquals(0, BenchRunner.Preset.LIGHT.sustainedSeconds)
    }
}
