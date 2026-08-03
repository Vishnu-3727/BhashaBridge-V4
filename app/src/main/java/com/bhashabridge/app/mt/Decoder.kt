package com.bhashabridge.app.mt

/**
 * Purpose:  The decoding abstraction. Turns a stream of next-token logits into a target token
 *           sequence. This is the ONLY thing MtEngine will depend on for its choice of decode
 *           strategy — greedy or beam is a swap of the [Decoder] instance, nothing else.
 * Owns:     Nothing. A decoder holds no native resources and no per-translation state that outlives
 *           a [decode] call.
 * Thread:   [decode] is synchronous and reentrant; run it on whatever thread MtEngine uses.
 *
 * A decoder knows nothing about ONNX Runtime, tokenizers, encoders, or Android. Its entire view of
 * the model is [LogitsSource]. That is deliberate: when the KV-cache export lands (Phase 5), the
 * source changes from "re-feed the whole prefix" to "feed one token + cached state" and NO decoder
 * code changes. The abstraction is the seam that isolates that rewrite.
 */
interface Decoder {

    /**
     * Decodes one translation.
     *
     * @param logits     the model, seen only as next-token logits (bound to one encoder output).
     * @param sourceLen  source token count, used for the length cap (`max(minTargetLen, sourceLen)`).
     * @return the generated target token ids **including** the leading start token. The caller
     *         (MtEngine) drops the start token and detokenizes; length-normalised scoring inside a
     *         decoder counts the start token, so it is returned rather than stripped here.
     */
    fun decode(logits: LogitsSource, sourceLen: Int): LongArray
}

/**
 * The decoder's whole view of the model: given the target prefix generated so far, produce raw
 * logits for the next token. One instance is bound to one encoder output for one translation.
 *
 * Today MtEngine will back this with a decoder ONNX session that takes the full prefix and returns
 * logits for every position, of which only the last row is handed back here. After the KV-cache
 * export it will feed only the last token plus cached key/values — same signature, same decoders.
 */
fun interface LogitsSource {
    /** @param prefix the full target prefix so far (starts with the start token). */
    fun nextLogits(prefix: LongArray): FloatArray
}

/**
 * Decoding parameters. Values mirror v3.4.1's `Translator` so the parity gate has a like-for-like
 * baseline; they are plain data here, not a [com.bhashabridge.app] RuntimeConfig (which Phase 4
 * does not build).
 *
 * `startToken == eosToken == 2` is correct, not a typo: IndicTrans2 (mBART-family) uses one `</s>`
 * id as both "begin decoding" and "stop".
 */
data class DecodeConfig(
    val startToken: Long = 2L,
    val eosToken: Long = 2L,
    /**
     * The absolute ceiling on generated tokens — a runaway guard, not the normal stop. The normal stop
     * is EOS, and failing that [targetCap].
     *
     * **This was 18, and that was a defect.** [targetCap] says a translation may run to the source
     * length, but both decoders loop `0 until maxSteps`, so 18 was the real limit and every input over
     * 18 tokens was silently cut off mid-sentence with nothing shown to the user. v3.4.1 shipped the
     * same pair of numbers.
     *
     * 128 is past any sentence a user types and still bounds the worst case, which matters because
     * `Tokenizer.encode` applies no source-length limit: an unbounded cap would let a long pasted
     * passage run hundreds of sequential decoder steps. At the SM-M315F's ~45 ms/step (the slowest
     * device in the cross-device database) 128 steps is ~5.8 s.
     *
     * ponytail: one ceiling for both the runaway case and the paste case. If pasted passages become a
     * real use case, cap the *input* in the UI and raise this, rather than splitting it in two.
     */
    val maxSteps: Int = 128,
    val minTargetLen: Int = 14,
    val repetitionPenalty: Float = 1.1f,
    val noRepeatNgram: Int = 3,
) {
    /**
     * The length cap for one translation: never shorter than [minTargetLen], grows with the input, and
     * never past [maxSteps]. Clamping here rather than in the decoders keeps a single number bounding
     * generation — the loop bound and the cap can no longer disagree, which is what hid the truncation.
     */
    fun targetCap(sourceLen: Int): Int = maxOf(minTargetLen, sourceLen).coerceAtMost(maxSteps)
}

// --- Shared decode rules. Both decoders apply these to a logits row before selecting tokens. ---
// Kept as internal top-level functions rather than a base class: they are pure transforms on a
// FloatArray with no state to inherit, and a one-method superclass would be the kind of ceremony
// ARCHITECTURE_RULES.md R0 exists to stop.

/**
 * Damps tokens already present in [prefix] so the decoder is less likely to loop. A positive logit
 * is divided by the penalty, a negative one multiplied — both push the value toward zero. Each
 * distinct token is penalised once. Mutates [logits] in place.
 */
internal fun applyRepetitionPenalty(logits: FloatArray, prefix: LongArray, penalty: Float) {
    if (penalty == 1.0f) return
    for (tok in prefix.toHashSet()) {
        val i = tok.toInt()
        if (i in logits.indices) {
            logits[i] = if (logits[i] > 0f) logits[i] / penalty else logits[i] * penalty
        }
    }
}

/**
 * Hard-forbids any token that would repeat an already-emitted [n]-gram, by driving its logit to
 * -1e9. Finds the current (n-1)-token suffix, scans earlier positions for the same suffix, and
 * blocks whatever token followed it there. Mutates [logits] in place.
 */
internal fun blockRepeatedNgrams(logits: FloatArray, prefix: LongArray, n: Int) {
    if (n <= 0 || prefix.size < n) return
    val suffixStart = prefix.size - (n - 1)
    for (i in 0..prefix.size - n) {
        var match = true
        for (k in 0 until n - 1) {
            if (prefix[i + k] != prefix[suffixStart + k]) { match = false; break }
        }
        if (match) {
            val blocked = prefix[i + n - 1].toInt()
            if (blocked in logits.indices) logits[blocked] = -1e9f
        }
    }
}

/** Index of the maximum logit; ties resolve to the lowest index, matching v3.4.1's strict `>` scan. */
internal fun argmax(logits: FloatArray): Int {
    var bestIdx = 0
    var bestVal = Float.NEGATIVE_INFINITY
    for (i in logits.indices) {
        if (logits[i] > bestVal) { bestVal = logits[i]; bestIdx = i }
    }
    return bestIdx
}
