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
        Tokenizer(src, tgtIdToPiece = emptyArray(), srcLangId = 4, tgtLangId = 15)

    /** Builds the id-indexed target array these tests used to express as a map literal. */
    private fun idToPiece(vararg pairs: Pair<Int, String>): Array<String?> {
        val out = arrayOfNulls<String>((pairs.maxOf { it.first }) + 1)
        pairs.forEach { (id, piece) -> out[id] = piece }
        return out
    }

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
            tgtIdToPiece = idToPiece(4 to "eng_Latn", 100 to "▁hello", 200 to "▁world", 2 to "</s>"),
            srcLangId = 4, tgtLangId = 15,
        )
        assertEquals("hello world", tok.decode(longArrayOf(4, 100, 200, 2)))
    }

    /**
     * The JSON says `b"c`, so the parser must produce `b"c`.
     *
     * This test previously asserted `b\"c` — backslash included — and so pinned the defect in place
     * instead of catching it: the parser appended the backslash *and* the character it escaped. The
     * shipped vocabularies contain four such pieces (`▁"`, `"`, `▁\`, `\`), which meant a typed
     * quote encoded as `<unk>` and a generated quote reached the screen as `\"`.
     */
    @Test
    fun `flat dict parser decodes escaped quotes and backslashes`() {
        val map = Tokenizer.parseFlatIntDict(StringReader("""{"a": 1, "b\"c": -2, "d\\e": 3}"""))
        assertEquals(mapOf("a" to 1, "b\"c" to -2, "d\\e" to 3), map)
    }

    /**
     * `\uXXXX` is absent from all four shipped dictionaries today — but only because the exporter
     * writes `ensure_ascii=False`. If that ever flips, every non-ASCII piece arrives escaped, and a
     * parser that did not understand `\u` would corrupt the entire target vocabulary silently.
     */
    @Test
    fun `flat dict parser decodes unicode escapes`() {
        // Not a raw string: the JSON has to contain the six characters \u2581, not the character ▁.
        val map = Tokenizer.parseFlatIntDict(StringReader("{\"\\u2581the\": 7, \"\\u0041\": 8}"))
        assertEquals(mapOf("▁the" to 7, "A" to 8), map)
    }

    /**
     * The four escaped pieces in the real EN→HI vocabularies, which is where this bug lived.
     *
     * `dict.SRC.json` carries `▁"` (id 12) and `\` (3647); `dict.TGT.json` carries `▁"` (7), `▁\`
     * (5839), `\` (18387) and `"` (100458). Before the fix the parser stored each of them under a
     * key one character too long, so the piece could never be matched on encode and arrived on
     * screen with a stray backslash on decode.
     */
    @Test
    fun `real dictionaries parse their escaped pieces to the right keys`() {
        val src = File("src/main/assets/dict.SRC.json")
        val tgt = File("src/main/assets/dict.TGT.json")
        assumeTrue("dictionaries not staged locally — skipping", src.exists() && tgt.exists())

        val srcMap = src.reader(Charsets.UTF_8).use { Tokenizer.parseFlatIntDict(it) }
        assertEquals("▁\" must be the two-character piece", 12, srcMap["▁\""])
        assertEquals("\\ must be the one-character piece", 3647, srcMap["\\"])
        assertTrue("the corrupted three-character key must be gone", !srcMap.containsKey("▁\\\""))

        val tgtMap = tgt.reader(Charsets.UTF_8).use { Tokenizer.parseFlatIntDict(it) }
        assertEquals(7, tgtMap["▁\""])
        assertEquals(100458, tgtMap["\""])
        assertEquals(5839, tgtMap["▁\\"])
        assertEquals(18387, tgtMap["\\"])
    }

    /**
     * Q4 (§3.48): the id-indexed target array must carry exactly what the map carried.
     *
     * The array starts at 128 K and **doubles** when an id lands past the end. That growth is the whole
     * risk in the change: a vocabulary whose highest id exceeded the initial capacity would otherwise
     * be silently truncated, and a dropped piece decodes to nothing — a wrong translation with no error
     * attached. Both the small case and an id past the initial capacity are checked here.
     */
    @Test
    fun `id-indexed target array matches the map it replaced`() {
        val arr = Tokenizer.parseIdToPiece(StringReader("""{"a": 1, "b": 5, "▁c": 2}"""))
        assertEquals("a", arr[1])
        assertEquals("b", arr[5])
        assertEquals("▁c", arr[2])
        assertEquals(null, arr[3]) // gaps stay null

        // Past the 128 K initial capacity: the doubling path, which nothing else exercises.
        val big = Tokenizer.parseIdToPiece(StringReader("""{"x": 1, "far": 200000}"""))
        assertEquals("far", big[200000])
        assertEquals("x", big[1])
    }

    /** The same check against the real vocabulary: every entry of the map resolves in the array. */
    @Test
    fun `real target dictionary survives the map to array change entry for entry`() {
        val tgt = File("src/main/assets/dict.TGT.json")
        assumeTrue("dictionaries not staged locally — skipping", tgt.exists())

        val map = tgt.reader(Charsets.UTF_8).use { Tokenizer.parseFlatIntDict(it) }
        val arr = tgt.reader(Charsets.UTF_8).use { Tokenizer.parseIdToPiece(it) }
        assertEquals("every id must resolve to the same piece", 0, map.count { (k, v) -> arr[v] != k })
    }

    /**
     * The parser reads 64 K characters at a time, so every entry it can be interrupted mid-way
     * through — a key, an escape pair, a number — has to survive the seam between two blocks. This
     * dictionary is far longer than one block and its entries do not align to it, so the seam lands
     * in a different place in each key.
     */
    @Test
    fun `flat dict parser spans read-block boundaries`() {
        val entries = (0 until 20_000).joinToString(",") { """"piece_${"x".repeat(it % 7)}$it": $it""" }
        val map = Tokenizer.parseFlatIntDict(StringReader("{$entries}"))

        assertEquals(20_000, map.size)
        assertEquals(0, map["piece_0"])
        assertEquals(19_999, map["piece_${"x".repeat(19_999 % 7)}19999"])
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
        val ids = Tokenizer(src, emptyArray(), srcLang, tgtLang).encode("hello world").toList()
        assertEquals("starts with source lang id", srcLang, ids.first())
        assertEquals("second is target lang id", tgtLang, ids[1])
        assertEquals("ends with </s>=2", 2L, ids.last())
    }
}
