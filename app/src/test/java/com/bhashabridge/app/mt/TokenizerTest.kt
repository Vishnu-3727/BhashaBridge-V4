package com.bhashabridge.app.mt

import com.bhashabridge.app.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader

/**
 * Tokenizer parity with v3.4.1, on the JVM. The pure-algorithm tests always run; the last one loads
 * the real gitignored dictionaries and is skipped where they are absent (R14.5 keeps them out of
 * git). Encode/decode logic is a line-for-line reimplementation of v3.4.1's `SentencePieceTokenizer`,
 * so matching structure here is parity by construction.
 */
class TokenizerTest {

    private val srcDict = mapOf(
        "eng_Latn" to 4, "hin_Deva" to 15, "</s>" to 2, "<unk>" to 3,
        "▁hello" to 100, "▁world" to 200, "▁he" to 50, "llo" to 51,
    )

    private fun enHi(src: Map<String, Int> = srcDict) =
        Tokenizer(src, tgtIdToPiece = emptyMap(), srcLangId = 4, tgtLangId = 15)

    @Test
    fun `encode wraps subwords with lang ids and eos`() {
        assertEquals(
            listOf(4L, 15L, 100L, 200L, 2L),
            enHi().encode("Hello world").toList(),
        )
    }

    @Test
    fun `greedy fallback splits an unknown word by longest match`() {
        // No "▁hello" entry here, so the word falls to greedy: "▁he" + "llo".
        val src = srcDict - "▁hello"
        assertEquals(listOf(4L, 15L, 50L, 51L, 2L), enHi(src).encode("hello").toList())
    }

    @Test
    fun `unmatched characters become unk`() {
        val src = mapOf("eng_Latn" to 4, "hin_Deva" to 15, "</s>" to 2, "<unk>" to 3)
        // "▁xz" has no piece and no substring in the dict: ▁, x, z each -> <unk> (3).
        assertEquals(listOf(4L, 15L, 3L, 3L, 3L, 2L), enHi(src).encode("xz").toList())
    }

    @Test
    fun `decode drops specials and lang tags and restores spaces`() {
        val tok = Tokenizer(
            srcPieceToId = emptyMap(),
            tgtIdToPiece = mapOf(4 to "eng_Latn", 100 to "▁hello", 200 to "▁world", 2 to "</s>"),
            srcLangId = 4, tgtLangId = 15,
        )
        assertEquals("hello world", tok.decode(longArrayOf(4, 100, 200, 2)))
    }

    @Test
    fun `flat dict parser reads keys with escaped quotes`() {
        val map = Tokenizer.parseFlatIntDict(StringReader("""{"a": 1, "b\"c": -2, "d": 3}"""))
        assertEquals(mapOf("a" to 1, "b\\\"c" to -2, "d" to 3), map)
    }

    /** Grounds lang ids and the </s> terminator against the real EN→HI vocabulary. */
    @Test
    fun `real EN-HI dictionary yields eng_Latn hin_Deva and eos`() {
        val f = File("src/main/assets/dict.SRC.json")
        assumeTrue("dict.SRC.json not staged locally — skipping", f.exists())

        val src = f.reader(Charsets.UTF_8).use { Tokenizer.parseFlatIntDict(it) }
        assertTrue("eng_Latn present", src.containsKey("eng_Latn"))
        assertTrue("hin_Deva present", src.containsKey("hin_Deva"))

        val (srcLang, tgtLang) = Tokenizer.langIds(Direction.EN_TO_HI, src)
        val ids = Tokenizer(src, emptyMap(), srcLang, tgtLang).encode("hello world").toList()
        assertEquals("starts with source lang id", srcLang, ids.first())
        assertEquals("second is target lang id", tgtLang, ids[1])
        assertEquals("ends with </s>=2", 2L, ids.last())
    }
}
