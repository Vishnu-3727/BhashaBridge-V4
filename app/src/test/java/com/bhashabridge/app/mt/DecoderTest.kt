package com.bhashabridge.app.mt

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Correctness of the decode algorithms against a synthetic [LogitsSource] — no ONNX, no Android, no
 * model. This proves the *algorithms* (argmax, EOS/cap stopping, penalties, beam scoring/pruning),
 * NOT parity with v3.4.1's output: parity needs the real decoder graph and is a Phase 5/6 on-device
 * check. See DECODING_ARCHITECTURE.md.
 */
class DecoderTest {

    // A table-driven model: the next-token logits depend only on the last token of the prefix.
    // Unlisted last-tokens return a flat row — a dead end whose log-probabilities are all poor.
    private class TableSource(private val rows: Map<Long, FloatArray>, val vocab: Int) : LogitsSource {
        override fun nextLogits(prefix: LongArray): FloatArray =
            (rows[prefix.last()] ?: FloatArray(vocab)).copyOf()
    }

    // start=0, eos=5, penalties off, cap kept out of the way so step count is what stops decoding.
    private val cfg = DecodeConfig(
        startToken = 0, eosToken = 5, maxSteps = 3, minTargetLen = 8,
        repetitionPenalty = 1.0f, noRepeatNgram = 0,
    )

    /**
     * A model where the immediately-best first token (1) leads to a worse total than the
     * second-best (2), which opens a high-scoring continuation. Greedy must take 1; beam must look
     * ahead and take the 2→3 path.
     */
    private fun lookaheadModel() = TableSource(
        rows = mapOf(
            0L to floatArrayOf(-10f, 2.0f, 1.8f, -10f, -10f, -10f), // start: t1 > t2
            1L to floatArrayOf(-10f, -10f, -10f, -10f, 0.0f, 0.5f), // after t1: weak, then eos
            2L to floatArrayOf(-10f, -10f, -10f, 3.0f, -10f, -10f), // after t2: strong t3
            3L to floatArrayOf(-10f, -10f, -10f, -10f, -10f, 0.5f), // after t3: eos
        ),
        vocab = 6,
    )

    @Test
    fun `greedy takes the locally-best token and stops at eos`() {
        val out = GreedyDecoder(cfg).decode(lookaheadModel(), sourceLen = 8)
        assertArrayEquals(longArrayOf(0, 1), out) // start, t1, then eos (not appended)
    }

    @Test
    fun `beam looks ahead and beats greedy`() {
        val out = BeamSearchDecoder(beamWidth = 2, config = cfg).decode(lookaheadModel(), sourceLen = 8)
        assertArrayEquals(longArrayOf(0, 2, 3), out)
    }

    @Test
    fun `beam width 1 is greedy`() {
        val model = lookaheadModel()
        val greedy = GreedyDecoder(cfg).decode(model, 8)
        val beam1 = BeamSearchDecoder(beamWidth = 1, config = cfg).decode(model, 8)
        assertArrayEquals(greedy, beam1)
    }

    @Test
    fun `length cap stops decoding without emitting eos`() {
        // Model never chooses eos: last token always prefers t1. Cap must halt it.
        val alwaysT1 = TableSource(mapOf(0L to r(1), 1L to r(1)), vocab = 6)
        val capped = cfg.copy(maxSteps = 100, minTargetLen = 3)
        // Expected length comes from `targetCap` rather than a literal, so this test stays about
        // "the cap halts a model that never emits EOS" and does not have to be re-derived every
        // time the cap's growth term changes.
        val cap = capped.targetCap(sourceLen = 3)
        val out = GreedyDecoder(capped).decode(alwaysT1, sourceLen = 3)
        assertEquals(cap, out.size)                              // start + (cap-1) generated, then halt
        assertEquals(cap.toLong(), out.count { it != 5L }.toLong()) // no eos in the result
    }

    /**
     * The regression that would have caught the truncation defect: a 40-token source must be allowed
     * 40 generated tokens. Both decoders loop `0 until maxSteps`, which defaulted to 18, so anything
     * longer was cut off mid-sentence with no signal — [DecodeConfig.targetCap] promised the source
     * length and never got it.
     */
    @Test
    fun `a long source is allowed to decode past the old 18-step limit`() {
        val alwaysT1 = TableSource(mapOf(0L to r(1), 1L to r(1)), vocab = 6)
        val defaults = DecodeConfig(startToken = 0, eosToken = 5, repetitionPenalty = 1.0f, noRepeatNgram = 0)
        val out = GreedyDecoder(defaults).decode(alwaysT1, sourceLen = 40)
        assertEquals("start token + 39 generated", 40 * 16 / 10 + 8, out.size)
    }

    /**
     * The half of that fix which shipped incomplete: a target may be **longer than its source**.
     *
     * Raising `maxSteps` to 128 removed one ceiling and left `targetCap = max(14, sourceLen)`, which
     * assumes a translation never needs more tokens than its input. Hindi expands; the sentence was
     * still cut off mid-way with no EOS and no signal, just at a different threshold.
     */
    @Test
    fun `the cap leaves room for a target longer than its source`() {
        val cfg = DecodeConfig()
        assertEquals("20-token source gets 40 tokens of room", 40, cfg.targetCap(20))
        assertEquals("a 40-token source is not capped at 40", 72, cfg.targetCap(40))
        // Short inputs lean on the +8 and the floor rather than on the ratio.
        assertEquals(14, cfg.targetCap(1))
        assertEquals(24, cfg.targetCap(10))
    }

    /** ...but not without a ceiling: `Tokenizer.encode` never truncates, so the cap must clamp. */
    @Test
    fun `an absurd source length clamps to maxSteps`() {
        val alwaysT1 = TableSource(mapOf(0L to r(1), 1L to r(1)), vocab = 6)
        val cfg = DecodeConfig(startToken = 0, eosToken = 5, repetitionPenalty = 1.0f, noRepeatNgram = 0)
        assertEquals(cfg.maxSteps, cfg.targetCap(sourceLen = 5000))
        assertEquals(cfg.maxSteps, GreedyDecoder(cfg).decode(alwaysT1, sourceLen = 5000).size)
    }

    /** A logits row of `vocab` size with token [best] set high, rest low. */
    private fun r(best: Int, vocab: Int = 6) = FloatArray(vocab) { if (it == best) 5f else -10f }

    // --- Pure rule helpers ---

    @Test
    fun `argmax returns the lowest index on ties`() {
        assertEquals(1, argmax(floatArrayOf(1f, 3f, 3f, 2f)))
    }

    @Test
    fun `repetition penalty damps each distinct prior token once`() {
        val logits = floatArrayOf(2f, -2f, 4f)
        applyRepetitionPenalty(logits, prefix = longArrayOf(0, 0, 1), penalty = 2f)
        assertEquals(1f, logits[0], 1e-6f)  // positive divided, once despite repeat
        assertEquals(-4f, logits[1], 1e-6f) // negative multiplied, pushed further down
        assertEquals(4f, logits[2], 1e-6f)  // untouched
    }

    @Test
    fun `no-repeat-ngram blocks the token that would recreate a seen ngram`() {
        val logits = floatArrayOf(1f, 1f, 1f, 1f)
        // prefix 0,1,2,0,1 with n=3: the bigram (0,1) was followed by 2 earlier, so 2 is blocked.
        blockRepeatedNgrams(logits, prefix = longArrayOf(0, 1, 2, 0, 1), n = 3)
        assertEquals(-1e9f, logits[2], 0f)
        assertEquals(1f, logits[0], 0f)
        assertEquals(1f, logits[1], 0f)
        assertEquals(1f, logits[3], 0f)
    }
}
