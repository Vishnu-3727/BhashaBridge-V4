package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The corpus measurement behind queue item Q0: how often did the length cap actually truncate?
 *
 * `targetCap` was `max(14, sourceLen)` — a target could never exceed its source token count — and
 * that was argued to truncate real sentences rather than measured to. This runs a spread of
 * sentence lengths through the real engine and reports, per sentence, the source token count, the
 * generated token count, and both caps: the one that shipped and the one that replaced it.
 *
 * A sentence is counted as **truncated under the old rule** when its generated length reached that
 * rule's cap — i.e. the translation the user sees today would have stopped mid-sentence.
 *
 * Generated length is counted by decoding to EOS through the same stage-level path `MtWorkload`
 * uses, not by re-encoding the output (re-encoding Hindi with the English vocabulary misses every
 * word and inflates the count — a trap this project has already fallen into once).
 *
 * Results are logged under `BB.Corpus`. Drive with:
 * `adb shell am instrument -w -e class com.bhashabridge.app.mt.TruncationCorpusTest \
 *   com.bhashabridge.app.test/androidx.test.runner.AndroidJUnitRunner`
 */
@RunWith(AndroidJUnit4::class)
class TruncationCorpusTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    /**
     * Wraps the shipping decoder and records what it produced.
     *
     * `MtEngine` takes its `Decoder` as a constructor parameter, so the count is the real one — same
     * argmax, same repetition penalty, same n-gram blocking, same cap — with no production code
     * added for the sake of a measurement and no decode logic reimplemented in a test.
     */
    private class CountingDecoder(private val inner: Decoder) : Decoder {
        var lastGenerated = 0
            private set

        override fun decode(logits: LogitsSource, sourceLen: Int): LongArray =
            inner.decode(logits, sourceLen).also { lastGenerated = it.size - 1 } // minus start token
    }

    @Test
    fun capHitRateAcrossSentenceLengths() {
        val counter = CountingDecoder(GreedyDecoder())
        val engine = MtEngine(app, Direction.EN_TO_HI, decoder = counter)
        val tokenizer = Tokenizer.load(app, Direction.EN_TO_HI)
        val config = DecodeConfig()

        var truncatedOld = 0
        var truncatedNew = 0

        try {
        for (sentence in CORPUS) {
            val srcLen = tokenizer.encode(sentence).size
            val output = engine.translate(sentence)
            val generated = counter.lastGenerated

            val oldCap = maxOf(config.minTargetLen, srcLen).coerceAtMost(config.maxSteps)
            val newCap = config.targetCap(srcLen)
            val hitOld = generated >= oldCap - 1
            val hitNew = generated >= newCap - 1
            if (hitOld) truncatedOld++
            if (hitNew) truncatedNew++

            Log.i(
                TAG,
                "REPORT src=$srcLen generated=$generated old_cap=$oldCap new_cap=$newCap " +
                    "hit_old=$hitOld hit_new=$hitNew | ${sentence.take(48)}",
            )
        }
        } finally {
            engine.release()
        }

        val n = CORPUS.size
        Log.i(TAG, "REPORT SUMMARY n=$n truncated_under_old_rule=$truncatedOld truncated_under_new_rule=$truncatedNew")
        Log.i(TAG, "REPORT SUMMARY old_rate=${100 * truncatedOld / n}% new_rate=${100 * truncatedNew / n}%")

        assertTrue(
            "the new cap must truncate no more often than the old one",
            truncatedNew <= truncatedOld,
        )
    }

    /**
     * Quotation marks, end to end — the user-visible half of the dictionary escape defect.
     *
     * Before the parser fix, `▁"` was stored under a key that input text cannot produce, so a typed
     * quote encoded as `<unk>`, and the generated quote token carried a literal backslash into the
     * output and into text-to-speech.
     */
    @Test
    fun quotedTextSurvivesTheRoundTrip() {
        val engine = app.translator(Direction.EN_TO_HI)

        for (sentence in QUOTED) {
            val output = engine.translate(sentence)
            Log.i(TAG, "REPORT quoted in=$sentence out=$output")
            assertTrue(
                "a backslash must never reach the output: $output",
                !output.contains('\\'),
            )
        }
    }

    private companion object {
        const val TAG = "BB.Corpus"

        /** Short to long, the shape the cap is sensitive to. */
        val CORPUS = listOf(
            "Water.",
            "Thank you.",
            "Where is the hospital?",
            "Hello, how are you?",
            "I need a doctor immediately.",
            "Please call an ambulance right now.",
            "The weather is very nice today and I want to go outside.",
            "Could you please tell me how to get to the railway station from here?",
            "My name is Vishnu and I am travelling from Bangalore to Delhi tomorrow morning.",
            "I have been waiting for the bus for more than an hour and it has still not arrived.",
            "The doctor told me that I should take this medicine twice a day after eating food.",
            "If you need any help with your luggage please tell me and I will carry it for you.",
            "We are looking for a hotel near the airport that is not too expensive and has clean rooms.",
            "She explained that the train would be delayed by three hours because of heavy rain in the region.",
            "Before you leave the building please make sure that all the windows are closed and the lights are switched off.",
            "The teacher asked the students to write a short essay about their summer holidays and to submit it by Friday.",
        )

        val QUOTED = listOf(
            """He said "hello" to me.""",
            """The sign says "no entry" here.""",
        )
    }
}
