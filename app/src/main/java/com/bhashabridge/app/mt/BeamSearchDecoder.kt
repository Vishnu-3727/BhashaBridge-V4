package com.bhashabridge.app.mt

import kotlin.math.exp
import kotlin.math.ln

/**
 * Purpose:  Beam search — keep the [beamWidth] best partial sequences alive instead of committing
 *           to one token per step, and return the best complete one by length-normalised score.
 *           v3.4.1 implemented this but never called it; here it is a real, selectable strategy.
 * Owns:     Nothing; one [decode] call holds only local state.
 * Thread:   Synchronous, reentrant.
 *
 * Cost note, not optimisation: with the current uncached decoder graph, each active beam is a full
 * forward pass through [LogitsSource] every step, so a width-K search does ~K× the decoder work of
 * greedy per step (plus beams run at least as long). Whether that buys enough quality to justify the
 * latency on the target device is the question Phase 4's benchmark exists to answer — this class
 * does not presume it does.
 */
class BeamSearchDecoder(
    private val beamWidth: Int = 2,
    private val config: DecodeConfig = DecodeConfig(),
) : Decoder {

    private class Beam(val tokens: LongArray, val logProb: Float, val finished: Boolean) {
        // Length-normalised so the search does not simply prefer the shortest sequence.
        val score: Float get() = logProb / tokens.size
    }

    override fun decode(logits: LogitsSource, sourceLen: Int): LongArray {
        val cap = config.targetCap(sourceLen)
        var beams = listOf(Beam(longArrayOf(config.startToken), 0f, false))
        val completed = ArrayList<Beam>()

        for (step in 0 until config.maxSteps) {
            if (beams.isEmpty()) break
            val candidates = ArrayList<Beam>(beams.size * beamWidth)

            for (beam in beams) {
                val row = logits.nextLogits(beam.tokens)
                applyRepetitionPenalty(row, beam.tokens, config.repetitionPenalty)
                blockRepeatedNgrams(row, beam.tokens, config.noRepeatNgram)

                val lse = logSumExp(row)
                for (idx in topKIndices(row, beamWidth)) {
                    val stepLogProb = beam.logProb + (row[idx] - lse)
                    val ends = idx.toLong() == config.eosToken || beam.tokens.size >= cap
                    val tokens = if (ends) beam.tokens else beam.tokens + idx.toLong()
                    candidates.add(Beam(tokens, stepLogProb, ends))
                }
            }

            // Rank all expansions; finished ones retire to `completed`, the best still-active
            // `beamWidth` survive to the next step.
            candidates.sortByDescending { it.score }
            val nextBeams = ArrayList<Beam>(beamWidth)
            for (c in candidates) {
                if (c.finished) completed.add(c)
                else if (nextBeams.size < beamWidth) nextBeams.add(c)
            }
            beams = nextBeams
        }

        completed.addAll(beams) // any still-active beams at the step cap are valid results too
        val best = completed.maxByOrNull { it.score } ?: return longArrayOf(config.startToken)
        return best.tokens
    }

    /**
     * Indices of the [k] largest logits, best first, via a partial insertion sort over k slots —
     * O(vocab × k) rather than O(vocab log vocab) for a full sort. For k=2 over a ~64k vocab that is
     * two linear passes. Mirrors v3.4.1's `topKIndices`.
     */
    private fun topKIndices(logits: FloatArray, k: Int): IntArray {
        val idx = IntArray(k) { -1 }
        val vals = FloatArray(k) { Float.NEGATIVE_INFINITY }
        for (i in logits.indices) {
            if (logits[i] > vals[k - 1]) {
                idx[k - 1] = i
                vals[k - 1] = logits[i]
                var j = k - 1
                while (j > 0 && vals[j] > vals[j - 1]) {
                    vals[j] = vals[j - 1].also { vals[j - 1] = vals[j] }
                    idx[j] = idx[j - 1].also { idx[j - 1] = idx[j] }
                    j--
                }
            }
        }
        return idx.filter { it >= 0 }.toIntArray()
    }

    /** Numerically stable log-sum-exp, for turning logits into log-probabilities. */
    private fun logSumExp(logits: FloatArray): Float {
        val max = logits.max()
        var sum = 0.0
        for (v in logits) sum += exp((v - max).toDouble())
        return max + ln(sum).toFloat()
    }
}
