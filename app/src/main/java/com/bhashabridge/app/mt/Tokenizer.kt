package com.bhashabridge.app.mt

import android.content.Context
import com.bhashabridge.app.Direction
import java.io.InputStreamReader
import java.io.Reader

/**
 * Purpose:  Converts text to the token-id sequence IndicTrans2's ONNX graphs expect, and decoder
 *           output ids back to text. A from-scratch dict-driven SentencePiece-style matcher — NOT a
 *           wrapper around Google's SentencePiece. It reads only the `dict.*.json` piece→id maps;
 *           the `.model` protobufs shipped in v3.4.1 assets are never opened (they were dead weight
 *           there too).
 * Owns:     Two in-memory vocab maps. Pure JVM heap, no native resources, nothing to release.
 * Thread:   Immutable after construction; [encode]/[decode] are safe from any thread.
 *
 * The encode/decode logic mirrors v3.4.1's `SentencePieceTokenizer` exactly so the Phase 5 parity
 * check has a like-for-like baseline. The pure core (maps + [encode]/[decode]) is separated from
 * asset loading so it is unit-testable on the JVM against the real dictionaries.
 */
class Tokenizer internal constructor(
    private val srcPieceToId: Map<String, Int>,
    private val tgtIdToPiece: Map<Int, String>,
    private val srcLangId: Long,
    private val tgtLangId: Long,
) {

    /**
     * Builds `[srcLang, tgtLang, <subwords…>, </s>]`. Each whitespace word is tried as a whole
     * against the vocab in three case variants (lower/title/upper), each prefixed with the
     * SentencePiece word-boundary marker ▁; on a miss the word falls back to [greedyEncode].
     */
    fun encode(text: String): LongArray {
        val words = text.trim().split(WHITESPACE)
        val ids = ArrayList<Long>(words.size * 2 + 3)
        ids.add(srcLangId)
        ids.add(tgtLangId)

        for (word in words) {
            if (word.isEmpty()) continue
            val lower = word.lowercase()
            val title = lower.replaceFirstChar { it.uppercaseChar() }
            val upper = lower.uppercase()
            val id = srcPieceToId["$MARK$lower"] ?: srcPieceToId["$MARK$title"] ?: srcPieceToId["$MARK$upper"]
            if (id != null) ids.add(id.toLong()) else ids.addAll(greedyEncode("$MARK$lower"))
        }

        ids.add((srcPieceToId["</s>"] ?: 2).toLong())
        return ids.toLongArray()
    }

    /** Greedy longest-match-first subword split, up to 20 chars; an unmatched char becomes `<unk>`. */
    private fun greedyEncode(text: String): List<Long> {
        val unk = (srcPieceToId["<unk>"] ?: 3).toLong()
        val out = ArrayList<Long>(text.length)
        var pos = 0
        while (pos < text.length) {
            var matched = false
            for (end in minOf(text.length, pos + 20) downTo pos + 1) {
                val id = srcPieceToId[text.substring(pos, end)]
                if (id != null) { out.add(id.toLong()); pos = end; matched = true; break }
            }
            if (!matched) { out.add(unk); pos++ }
        }
        return out
    }

    /**
     * Drops special ids (0/1/2/3) and anything shaped like a language tag, maps the rest back to
     * pieces, joins, and turns ▁ back into spaces. Language tags are filtered by shape, not a fixed
     * list, so one leaking mid-sequence cannot reach visible output.
     */
    fun decode(ids: LongArray): String =
        ids.asSequence()
            .filter { it !in SPECIAL }
            .mapNotNull { tgtIdToPiece[it.toInt()] }
            .filter { !LANG_TAG.matches(it) }
            .joinToString("")
            .replace(MARK, " ")
            .trim()

    companion object {
        private const val MARK = "▁"          // ▁ SentencePiece word-boundary marker
        private val WHITESPACE = "\\s+".toRegex()
        private val LANG_TAG = Regex("^[a-z]{2,3}_[A-Z][a-z]{3,}$")
        private val SPECIAL = setOf(0L, 1L, 2L, 3L)

        /** Loads the pair of dictionaries for [direction] from assets. */
        fun load(context: Context, direction: Direction): Tokenizer {
            val (srcDict, tgtDict) = when (direction) {
                Direction.EN_TO_HI -> "dict.SRC.json" to "dict.TGT.json"
                Direction.HI_TO_EN -> "dict.SRC_HI.json" to "dict.TGT_EN.json"
            }
            val src = context.assets.open(srcDict).use { parseFlatIntDict(InputStreamReader(it, Charsets.UTF_8)) }
            val tgt = context.assets.open(tgtDict).use { parseFlatIntDict(InputStreamReader(it, Charsets.UTF_8)) }
            val (srcLang, tgtLang) = langIds(direction, src)
            return Tokenizer(src, tgt.entries.associate { (k, v) -> v to k }, srcLang, tgtLang)
        }

        // Language-tag ids come from IndicTrans2's tokenizer config; fallbacks apply only if the
        // dictionary lacks the exact key. Values differ by vocab file (EN→HI hin_Deva=15,
        // HI→EN hin_Deva=8), matching v3.4.1.
        internal fun langIds(direction: Direction, src: Map<String, Int>): Pair<Long, Long> = when (direction) {
            Direction.EN_TO_HI -> (src["eng_Latn"] ?: 4).toLong() to (src["hin_Deva"] ?: 15).toLong()
            Direction.HI_TO_EN -> (src["hin_Deva"] ?: 8).toLong() to (src["eng_Latn"] ?: 4).toLong()
        }

        /**
         * Parses a flat `{"piece": int, …}` object one character at a time — no parse tree, so a
         * multi-MB dictionary never materialises as one. Understands only this exact shape.
         */
        internal fun parseFlatIntDict(reader: Reader): Map<String, Int> {
            val map = HashMap<String, Int>(1 shl 16)
            val sb = StringBuilder()
            var inString = false
            var escape = false
            var key = ""
            var readingKey = true
            fun commit() {
                val id = sb.toString().trim().toIntOrNull()
                if (key.isNotEmpty() && id != null) map[key] = id
                key = ""; sb.clear(); readingKey = true
            }
            var c = reader.read()
            while (c != -1) {
                val ch = c.toChar()
                when {
                    escape -> { sb.append(ch); escape = false }
                    ch == '\\' && inString -> { sb.append(ch); escape = true }
                    ch == '"' && !inString -> inString = true
                    ch == '"' && inString -> { inString = false; if (readingKey) { key = sb.toString(); sb.clear() } }
                    ch == ':' && !inString -> { readingKey = false; sb.clear() }
                    ch == ',' && !inString -> commit()
                    ch == '}' && !inString -> commit()
                    inString -> sb.append(ch)
                    ch.isDigit() || ch == '-' -> sb.append(ch)
                }
                c = reader.read()
            }
            return map
        }
    }
}
