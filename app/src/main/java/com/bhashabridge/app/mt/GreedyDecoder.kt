package com.bhashabridge.app.mt

import com.bhashabridge.app.bench.Metrics

/**
 * Purpose:  Greedy decoding — take the single highest-scoring token at every step. The strategy
 *           v3.4.1 actually shipped for both directions.
 * Owns:     Nothing; one [decode] call holds only local state.
 * Thread:   Synchronous, reentrant.
 *
 * One decoder call = one forward pass through [LogitsSource] per generated token. No lookahead, no
 * alternatives kept. Behaviour matches `Translator.translateGreedy`: repetition penalty and
 * no-repeat-ngram blocking on each step, argmax selection, stop on `</s>` or the length cap.
 */
class GreedyDecoder(private val config: DecodeConfig = DecodeConfig()) : Decoder {

    override fun decode(logits: LogitsSource, sourceLen: Int): LongArray {
        val cap = config.targetCap(sourceLen)
        val generated = ArrayList<Long>(cap + 1)
        generated.add(config.startToken)

        for (step in 0 until config.maxSteps) {
            val prefix = generated.toLongArray()
            val row = logits.nextLogits(prefix)

            applyRepetitionPenalty(row, prefix, config.repetitionPenalty)
            blockRepeatedNgrams(row, prefix, config.noRepeatNgram)

            val next = argmax(row).toLong()

            // Matches v3.4.1 exactly: EOS and the length cap both stop WITHOUT appending the token
            // being examined. The cap is checked against the current length, before the append.
            if (next == config.eosToken) break
            if (generated.size >= cap) {
                // Stopping on the cap means the sentence was cut off, not finished. Counted so the
                // rate is observable on a real corpus instead of inferred from the formula — the
                // first version of this cap looked reasonable and truncated silently for months.
                // Debug-gated like every other Metrics call, so release builds are unaffected.
                Metrics.counter("hit_cap", 1)
                break
            }
            generated.add(next)
        }
        return generated.toLongArray()
    }
}
