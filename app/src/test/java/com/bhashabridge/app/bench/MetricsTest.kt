package com.bhashabridge.app.bench

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [Metrics.Run] directly — the pure part. Emission goes through android.util.Log, which is
 * not available on the JVM, so the ThreadLocal plumbing is verified on-device instead.
 *
 * Nanosecond values here are literals, not measurements: the point is that the arithmetic and the
 * JSON shape are right, and a real clock would make the assertions non-deterministic.
 */
class MetricsTest {

    private val start = 1_000_000_000L
    private fun ms(n: Long) = n * 1_000_000L

    @Test
    fun `stages attribute elapsed time in order`() {
        val run = Metrics.Run("translate", start)
        run.closeStage("encode", start + ms(40))
        run.closeStage("decode", start + ms(240))

        val json = run.toJson(start + ms(250))

        assertEquals(
            "{\"run\":\"translate\",\"total_ms\":250.000," +
                "\"stages\":{\"encode\":40.000,\"decode\":200.000}}",
            json,
        )
    }

    @Test
    fun `repeated stage name accumulates rather than overwrites`() {
        val run = Metrics.Run("loop", start)
        run.closeStage("step", start + ms(10))
        run.closeStage("step", start + ms(30))

        assertTrue(run.toJson(start + ms(30)).contains("\"step\":30.000"))
    }

    @Test
    fun `counters accumulate and emit as integers`() {
        val run = Metrics.Run("translate", start)
        run.addCounter("tokens", 12)
        run.addCounter("tokens", 6)
        run.addCounter("steps", 1)

        val json = run.toJson(start)

        assertTrue(json.contains("\"counters\":{\"tokens\":18,\"steps\":1}"))
    }

    @Test
    fun `empty stages and counters are omitted entirely`() {
        val json = Metrics.Run("bare", start).toJson(start + ms(5))

        assertEquals("{\"run\":\"bare\",\"total_ms\":5.000}", json)
    }

    /** A stray quote in a label must not produce a line the bench harness cannot parse. */
    @Test
    fun `quotes and backslashes in labels are escaped`() {
        val run = Metrics.Run("say \"hi\"", start)
        run.addCounter("back\\slash", 1)

        val json = run.toJson(start)

        assertTrue(json.contains("\"run\":\"say \\\"hi\\\"\""))
        assertTrue(json.contains("\"back\\\\slash\":1"))
    }

    /**
     * Guards against Locale.getDefault() creeping back in. On a Hindi or German locale, "%.3f"
     * yields "250,000" — valid-looking output that silently breaks every downstream parse.
     */
    @Test
    fun `millisecond formatting is locale independent`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            val json = Metrics.Run("locale", start).toJson(start + ms(250))
            assertTrue("decimal separator must be '.'", json.contains("\"total_ms\":250.000"))
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }
}
