package com.bhashabridge.app.speech

import com.bhashabridge.app.Direction

/**
 * Purpose:  Rule-based cleanup of Vosk output before it reaches the translator.
 * Owns:     Three immutable lookup tables.
 * Lifetime: Process
 * Thread:   Any — pure functions over their input.
 *
 * Vosk's Indian-English acoustic model makes predictable, repeatable mistakes (Indian-English
 * grammar patterns, informal contractions, a handful of homophone confusions) that a curated table
 * fixes far more cheaply than a grammar model would. Tables carried over from v3.4.1 unchanged —
 * they encode real observed ASR behaviour, and rewriting them would discard field evidence.
 *
 * Cost is ~50 substring scans plus one regex per contraction. Fine for one utterance; a trie or a
 * single combined regex would be needed for long-form text.
 */
object AsrCorrector {

    /** Corrects [text] for [direction]'s source language. */
    fun correct(text: String, direction: Direction): String = when (direction) {
        Direction.EN_TO_HI -> correctEnglish(text)
        Direction.HI_TO_EN -> correctHindi(text)
    }

    /**
     * Phrase repairs, applied on **word boundaries**.
     *
     * These were literal `contains`/`replace` substring matches, on the reasoning that each entry
     * carried enough context to be safe. Several do not: `"i is"` matches inside `"delhi is far"`,
     * which the corrector turned into **`"delhi am far"`** before the translator ever saw it. Any
     * entry whose first or last word is short enough to end another word is exposed the same way —
     * `"he want"`, `"i are"`, `"we is"`.
     *
     * Some entries used to carry a trailing space (`"she go "`) as a hand-rolled boundary. `\b` does
     * that properly and in both directions, so the spaces are gone: `\bshe go\b` will not fire on
     * `"she going"`, because there is no boundary between `o` and `i`.
     *
     * Compiled once at class-init rather than per utterance — same cost as the old `contains` scan
     * on a phrase this short, paid once instead of per recognition result.
     */
    private val englishPhrases: List<Pair<Regex, String>> = linkedMapOf(
        "what is you doing" to "what are you doing",
        "what is you" to "what are you",
        "where is you" to "where are you",
        "how is you" to "how are you",
        "he want to" to "i want to",
        "he wants to" to "i want to",
        "she want to" to "i want to",
        "the want to" to "i want to",
        "he want" to "i want",
        "he wants" to "i want",
        "she want" to "i want",
        "the want" to "i want",
        "i am feel" to "i am feeling",
        "i is" to "i am",
        "travel plain" to "travel plan",
        "traval plan" to "travel plan",
        "traval plain" to "travel plan",
        "i did went" to "i went",
        "i have went" to "i have gone",
        "more better" to "better",
        "more faster" to "faster",
        "more easier" to "easier",
        "can you able to" to "are you able to",
        "i am having hunger" to "i am hungry",
        "i am having thirst" to "i am thirsty",
        "i am having fever" to "i have fever",
        "she go" to "she goes",
        "he go" to "he goes",
        "it go" to "it goes",
        "they goes" to "they go",
        "we goes" to "we go",
        "i goes" to "i go",
        "you goes" to "you go",
        "she don't" to "she doesn't",
        "he don't" to "he doesn't",
        "it don't" to "it doesn't",
        "i are" to "i am",
        "you is" to "you are",
        "they is" to "they are",
        "we is" to "we are",
        "did you went" to "did you go",
        "have you went" to "have you gone",
        "calm the police" to "call the police",
        "calm an ambulance" to "call an ambulance",
        "calm a doctor" to "call a doctor",
        "calm my" to "call my",
    ).map { (wrong, fix) -> Regex("\\b" + Regex.escape(wrong) + "\\b") to fix }

    private val contractions = mapOf(
        "gonna" to "going to",
        "wanna" to "want to",
        "gotta" to "got to",
        "kinda" to "kind of",
        "coulda" to "could have",
        "woulda" to "would have",
        "shoulda" to "should have",
        "lemme" to "let me",
        "gimme" to "give me",
        "dunno" to "don't know",
        "hafta" to "have to",
        "tryna" to "trying to",
        "sorta" to "sort of",
        "lotta" to "lot of",
        "outta" to "out of",
    )

    /** Compiled once, like [englishPhrases] — this table was already boundary-correct, only re-compiled per call. */
    private val contractionPatterns: List<Pair<Regex, String>> =
        contractions.map { (wrong, fix) -> Regex("\\b" + Regex.escape(wrong) + "\\b") to fix }

    /** Vosk's Hindi model drops indirect-object case markers; these are the observed cases. */
    private val hindiPhrases = linkedMapOf(
        "मैं जाना है" to "मुझे जाना है",
        "मैं खाना है" to "मुझे खाना है",
        "मैं पानी है" to "मुझे पानी चाहिए",
    )

    private val fillers = Regex("\\b(um|uh|ah|er|hmm|hm|erm)\\b\\s*")
    private val whitespace = Regex("\\s+")

    private fun correctEnglish(input: String): String {
        if (input.isBlank()) return input
        var text = input.lowercase().trim().replace(fillers, "").replace(whitespace, " ").trim()
        if (text.isBlank()) return input

        // Literal replacements: the fixes contain no `$`, but going through Regex.replace's
        // replacement syntax for a table someone will edit later is a trap worth not leaving.
        for ((pattern, fix) in englishPhrases) {
            text = pattern.replace(text) { fix }
        }
        for ((pattern, fix) in contractionPatterns) {
            text = pattern.replace(text) { fix }
        }
        text = disambiguate(text.replace(whitespace, " ").trim())
        return text.trim().replaceFirstChar { it.uppercase() }
    }

    private fun correctHindi(input: String): String {
        if (input.isBlank()) return input
        var text = input.trim().replace(whitespace, " ").trim()
        for ((wrong, fix) in hindiPhrases) {
            if (text.contains(wrong)) text = text.replace(wrong, fix)
        }
        return text
    }

    /**
     * A 2–3 word lookahead heuristic, not a parser. A phrase table cannot fix this class of error
     * because the right correction depends on grammatical context, not on a fixed substring:
     * "there is you" and "there the doctor" open with the same mis-heard word and need different
     * repairs.
     */
    private fun disambiguate(text: String): String {
        val words = text.trim().split(whitespace)
        if (words.isEmpty()) return text
        val first = words[0].lowercase()
        val second = words.getOrElse(1) { "" }.lowercase()
        val third = words.getOrElse(2) { "" }.lowercase()
        val rest = { words.drop(1).joinToString(" ") }

        return when {
            first in WHERE_CONFUSIONS && second in BE_VERBS && third in PRONOUNS -> "where ${rest()}"
            first in WHERE_CONFUSIONS && second in PRONOUNS &&
                (third in GOING_VERBS || third.endsWith("ing")) -> "where are ${rest()}"
            first in setOf("then", "than") -> "when ${rest()}"
            first in WHERE_CONFUSIONS && second in setOf("the", "a", "an") -> "where is ${rest()}"
            else -> text
        }
    }

    private val WHERE_CONFUSIONS = setOf("that", "there", "wear", "were", "ware", "value")
    private val BE_VERBS = setOf("are", "is", "were", "was", "am")
    private val PRONOUNS = setOf("you", "he", "she", "they", "we", "i", "it")
    private val GOING_VERBS = setOf(
        "going", "doing", "coming", "saying", "eating", "drinking", "sitting", "standing", "running"
    )
}
