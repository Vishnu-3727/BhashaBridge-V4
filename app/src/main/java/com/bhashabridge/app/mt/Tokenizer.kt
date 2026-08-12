package com.bhashabridge.app.mt

import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Metrics
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
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
    /**
     * id → piece, as a **flat array indexed by token id**, not a map (Q4, §3.48).
     *
     * The ids are dense and small (0 … ~122,700), which is exactly what an array is for. The map this
     * replaced was built by inverting the target vocabulary after parsing it — 122,672 `Pair`s, 122,672
     * boxed `Integer`s and a second hash table, measured at **516 ms of every cold start** for a
     * structure an array addresses in one instruction.
     */
    private val tgtIdToPiece: Array<String?>,
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
            .mapNotNull { tgtIdToPiece.getOrNull(it.toInt()) }
            .filter { !LANG_TAG.matches(it) }
            .joinToString("")
            .replace(MARK, " ")
            .trim()

    companion object {
        private const val MARK = "▁"          // ▁ SentencePiece word-boundary marker
        private val WHITESPACE = "\\s+".toRegex()
        private val LANG_TAG = Regex("^[a-z]{2,3}_[A-Z][a-z]{3,}$")
        private val SPECIAL = setOf(0L, 1L, 2L, 3L)

        /**
         * The reader every dictionary parse goes through: UTF-8, buffered 64 KB.
         *
         * Phase 11B. [parseFlatIntDict] consumes one character per `Reader.read()`, and an
         * `InputStreamReader` services each of those calls through its `StreamDecoder`. On the
         * 3.39 MB target vocabulary that overhead measured **9951 ms**; the identical parser behind
         * this reader measured **1082 ms**, against a raw-I/O floor of 18 ms
         * (`StartupProbeTest.probeTokenizerLoad`, docs/ENGINE_STARTUP_ANALYSIS.md §3.1).
         *
         * A buffer changes when bytes are fetched, never how they are decoded or interpreted: same
         * UTF-8 charset, same character sequence, same parser, same map. 64 KB is one order above
         * the default 8 KB and comfortably above the largest dictionary's line structure; larger
         * buffers stop paying because the cost is per-call, not per-refill.
         */
        private fun bufferedUtf8(input: InputStream): Reader =
            BufferedReader(InputStreamReader(input, Charsets.UTF_8), BUFFER_BYTES)

        /** 64 KB. See [bufferedUtf8]. */
        private const val BUFFER_BYTES = 1 shl 16

        /**
         * Resolves a dictionary as a file in `filesDir`, falling back to the packaged asset.
         *
         * The same rule [OnnxModels] already applies to the `.onnx` graphs, for the same reason: it
         * lets a build that ships no assets — the `:benchapp` smoke test — run the real tokenizer
         * against sideloaded vocabularies, instead of needing a second implementation of it.
         *
         * The app itself never stages a dictionary into `filesDir`, so it always takes the asset
         * branch and its behaviour is unchanged; the cost is one `exists()` per vocabulary against a
         * parse that takes on the order of a second.
         */
        private fun openDict(context: Context, name: String): InputStream {
            val staged = File(context.filesDir, name)
            return if (staged.exists() && staged.length() > 0L) staged.inputStream()
            else context.assets.open(name)
        }

        /** Loads the pair of dictionaries for [direction]. See [openDict] for where they come from. */
        fun load(context: Context, direction: Direction): Tokenizer {
            val (srcDict, tgtDict) = when (direction) {
                Direction.EN_TO_HI -> "dict.SRC.json" to "dict.TGT.json"
                Direction.HI_TO_EN -> "dict.SRC_HI.json" to "dict.TGT_EN.json"
            }
            // Phase 11A: three marks, so the report can separate parsing the two vocabularies from
            // building the reverse index. Inline and debug-gated — release builds are unchanged.
            val src = openDict(context, srcDict).use { parseFlatIntDict(bufferedUtf8(it)) }
            Metrics.stage("tokenizer:src_dict")
            // Straight into the id-indexed array (Q4, §3.48). The target vocabulary is only ever read
            // as id → piece, so the map-then-invert this replaced built two structures to end up with
            // neither: the map was discarded and the inversion cost 516 ms on its own.
            val tgt = openDict(context, tgtDict).use { parseIdToPiece(bufferedUtf8(it)) }
            Metrics.stage("tokenizer:tgt_dict")
            val (srcLang, tgtLang) = langIds(direction, src)
            return Tokenizer(src, tgt, srcLang, tgtLang)
        }

        // Language-tag ids come from IndicTrans2's tokenizer config; fallbacks apply only if the
        // dictionary lacks the exact key. Values differ by vocab file (EN→HI hin_Deva=15,
        // HI→EN hin_Deva=8), matching v3.4.1.
        internal fun langIds(direction: Direction, src: Map<String, Int>): Pair<Long, Long> = when (direction) {
            Direction.EN_TO_HI -> (src["eng_Latn"] ?: 4).toLong() to (src["hin_Deva"] ?: 15).toLong()
            Direction.HI_TO_EN -> (src["hin_Deva"] ?: 8).toLong() to (src["eng_Latn"] ?: 4).toLong()
        }

        /**
         * Parses a flat `{"piece": int, …}` object character by character — no parse tree, so a
         * multi-MB dictionary never materialises as one. Understands only this exact shape.
         *
         * Characters are pulled a **block at a time** into [chunk] and then walked in the array.
         * `Reader.read()` per character is one virtual call plus a `synchronized` block on
         * `BufferedReader`'s lock for every one of the 3.4 M characters in the target vocabulary;
         * the identical state machine over a filled `CharArray` pays that once per 64 K characters
         * instead. Same charset, same character sequence, same branches, same map — only the
         * fetch granularity changed. This is the second half of the Phase 11B finding (the first
         * was [bufferedUtf8]); startup is dominated by these two parses.
         */
        internal fun parseFlatIntDict(reader: Reader): Map<String, Int> {
            val map = HashMap<String, Int>(1 shl 16)
            parseEntries(reader) { piece, id -> map[piece] = id }
            return map
        }

        /**
         * The same parse, straight into an **array indexed by id** — the shape the target vocabulary
         * is actually used in (Q4, §3.48).
         *
         * Building a `Map<String, Int>` and inverting it afterwards produced the same information via
         * two hash tables, 122,672 `Pair`s and two rounds of `Integer` boxing, and threw the first
         * table away. The ids are dense, so the array is both smaller and O(1) without hashing.
         *
         * The array grows by doubling rather than being sized from a constant: the vocabulary's
         * highest id is a property of the export, and a hard-coded bound would silently truncate the
         * tail of a larger one — dropped pieces decode as nothing, which is a wrong translation with
         * no error attached. Negative ids cannot index an array and are skipped; the JSON shape does
         * not produce them, and a corrupt file should not crash the tokenizer.
         */
        internal fun parseIdToPiece(reader: Reader): Array<String?> {
            var out = arrayOfNulls<String>(1 shl 17)
            parseEntries(reader) { piece, id ->
                if (id >= 0) {
                    if (id >= out.size) {
                        var size = out.size
                        while (size <= id) size = size shl 1
                        out = out.copyOf(size)
                    }
                    out[id] = piece
                }
            }
            return out
        }

        /**
         * The character-level state machine, shared by [parseFlatIntDict] and [parseIdToPiece].
         *
         * `inline` so [emit] is compiled into the loop body: this runs once per vocabulary entry —
         * 122,672 times for the target dictionary — and a megamorphic call there would cost more than
         * the structure the callback exists to build.
         */
        private inline fun parseEntries(reader: Reader, crossinline emit: (String, Int) -> Unit) {
            val sb = StringBuilder()
            var inString = false
            var escape = false
            var key = ""
            var readingKey = true
            // The commit was a local function until Q4 made this `inline`, which Kotlin does not allow
            // one inside. Both of its call sites — `,` and `}` — had identical bodies, so they are now
            // one branch with the body written out once; nothing about the state machine changed.
            // Escape state lives out here, not inside the per-chunk loop: a `\"` pair or a `\uXXXX`
            // sequence can straddle the 64 K seam between two reads.
            var unicodeRemaining = 0
            var unicodeAcc = 0
            val chunk = CharArray(BUFFER_BYTES)
            var read = reader.read(chunk)
            while (read != -1) {
                for (i in 0 until read) {
                    val ch = chunk[i]
                    when {
                        // \uXXXX — four hex digits, accumulated then emitted as one char.
                        unicodeRemaining > 0 -> {
                            unicodeAcc = unicodeAcc * 16 + Character.digit(ch, 16).coerceAtLeast(0)
                            if (--unicodeRemaining == 0) sb.append(unicodeAcc.toChar())
                        }
                        // Emit what the escape MEANS. This used to append the escaped character
                        // *after* the backslash had already been appended below, so `"▁\""` in the
                        // JSON became the three-character key `▁\"` instead of the piece `▁"`.
                        // Four of the 122,672 target pieces are affected, and both directions are
                        // wrong in both directions: a typed quote missed its piece and fell through
                        // to <unk>, and a quote token generated by the model reached the screen as
                        // a literal backslash-quote.
                        escape -> {
                            escape = false
                            when (ch) {
                                'u' -> { unicodeRemaining = 4; unicodeAcc = 0 }
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                'r' -> sb.append('\r')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('')
                                else -> sb.append(ch)   // \" \\ \/ — the escapes these vocabularies use
                            }
                        }
                        // The backslash itself is consumed, not appended. That was the defect.
                        ch == '\\' && inString -> escape = true
                        ch == '"' && !inString -> inString = true
                        ch == '"' && inString -> { inString = false; if (readingKey) { key = sb.toString(); sb.clear() } }
                        ch == ':' && !inString -> { readingKey = false; sb.clear() }
                        (ch == ',' || ch == '}') && !inString -> {
                            val id = sb.toString().trim().toIntOrNull()
                            if (key.isNotEmpty() && id != null) emit(key, id)
                            key = ""; sb.clear(); readingKey = true
                        }
                        inString -> sb.append(ch)
                        ch.isDigit() || ch == '-' -> sb.append(ch)
                    }
                }
                read = reader.read(chunk)
            }
        }
    }
}
