package com.bhashabridge.app.speech

import com.bhashabridge.app.Direction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `AsrCorrector` is 160 lines of pure string rules that every spoken utterance passes through before
 * the translator sees it, and it had no tests at all — which is how `"delhi is far"` became
 * `"delhi am far"` and stayed that way.
 *
 * These run on the JVM: no Android, no Vosk, no model.
 */
class AsrCorrectorTest {

    private fun en(text: String) = AsrCorrector.correct(text, Direction.EN_TO_HI)
    private fun hi(text: String) = AsrCorrector.correct(text, Direction.HI_TO_EN)

    // ---- The defect this file was written for ----------------------------------------------------

    /**
     * `"i is" -> "i am"` used to be a bare substring replace, so it fired inside any word ending in
     * `i` followed by `is`. The corrector silently damaged place names, and the translator was handed
     * the damaged text.
     */
    @Test
    fun `a phrase rule does not fire inside a longer word`() {
        assertEquals("Delhi is far from here", en("delhi is far from here"))
        assertEquals("Mumbai is very big", en("mumbai is very big"))
    }

    /** `"she go"` carried a trailing space as a hand-rolled boundary; `\b` has to do it properly. */
    @Test
    fun `a phrase rule does not fire on a longer following word`() {
        assertEquals("She going home", en("she going home"))
        assertEquals("He wanted tea", en("he wanted tea"))
    }

    // ---- The repairs still have to work ----------------------------------------------------------

    @Test
    fun `subject-verb repairs still apply on real boundaries`() {
        assertEquals("I am hungry", en("i is hungry"))
        assertEquals("She goes to work", en("she go to work"))
        assertEquals("I want water", en("he want water"))
        assertEquals("You are late", en("you is late"))
    }

    @Test
    fun `the emergency homophones are repaired`() {
        assertEquals("Call the police", en("calm the police"))
        assertEquals("Call an ambulance", en("calm an ambulance"))
    }

    @Test
    fun `contractions are expanded on word boundaries`() {
        assertEquals("I am going to leave", en("i am gonna leave"))
        assertEquals("Let me help you", en("lemme help you"))
        // Not inside another word.
        assertEquals("Gonnabe is not a word", en("gonnabe is not a word"))
    }

    @Test
    fun `fillers are removed and whitespace collapsed`() {
        assertEquals("I need a doctor", en("um i need   a doctor"))
        assertEquals("Where is the hospital", en("uh where is the hospital"))
    }

    @Test
    fun `the where-confusion heuristic rewrites the opening word`() {
        assertEquals("Where are you going", en("there you going"))
        assertEquals("When is the train", en("then is the train"))
    }

    // ---- Edges ------------------------------------------------------------------------------------

    @Test
    fun `blank and filler-only input come back untouched`() {
        assertEquals("", en(""))
        assertEquals("   ", en("   "))
        // Nothing but fillers would otherwise correct to the empty string.
        assertEquals("um uh", en("um uh"))
    }

    @Test
    fun `clean input is only capitalised`() {
        assertEquals("I would like some water please", en("i would like some water please"))
    }

    // ---- Hindi ------------------------------------------------------------------------------------

    /**
     * The Hindi table stays a plain substring replace: `\b` is defined on `[A-Za-z0-9_]`, so it does
     * not fire between Devanagari characters and would break these rules rather than protect them.
     */
    @Test
    fun `hindi case markers are repaired`() {
        assertEquals("मुझे जाना है", hi("मैं जाना है"))
        assertEquals("मुझे पानी चाहिए", hi("मैं पानी है"))
    }

    @Test
    fun `hindi text is not capitalised or lowercased`() {
        assertEquals("आज मौसम अच्छा है", hi("आज मौसम अच्छा है"))
    }
}
