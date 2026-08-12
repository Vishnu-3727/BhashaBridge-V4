package com.bhashabridge.app.mt

import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.bench.Metrics
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logWarn
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader

/**
 * id → piece over the packed vocabulary image, decoding a `String` only when one is asked for
 * (C1, §3.56).
 *
 * **Why this exists.** `decode` is the only reader of the target vocabulary and it performs about
 * twelve lookups per translation. Expanding all 122,672 entries into `String` objects at startup to
 * serve twelve was the largest single piece of work left in engine construction — 745 ms of a 1036 ms
 * tokenizer stage. The bytes are already on disk in exactly the right layout, so this keeps them and
 * indexes them.
 *
 * [slots] packs offset and length into one `Long` per id — high 32 bits offset, low 32 length — so the
 * index is one array rather than two. `0L` marks an id the vocabulary does not define: offset 0 is
 * inside the header and can never begin a piece, so it is a safe sentinel rather than a reserved value.
 *
 * The image is retained for the tokenizer's life, which is the trade: ~2.5 MB of bytes plus ~1 MB of
 * index against ~6.8 MB of `String` objects, so it is also the smaller of the two.
 */
internal class TargetVocabulary private constructor(
    private val image: ByteArray,
    private val slots: LongArray,
) {

    /** The piece for [id], or null when the vocabulary does not define it. */
    fun pieceAt(id: Int): String? {
        if (id < 0 || id >= slots.size) return null
        val slot = slots[id]
        if (slot == 0L) return null
        return String(image, (slot ushr 32).toInt(), (slot and 0xFFFFFFFFL).toInt(), Charsets.UTF_8)
    }

    /** Number of ids the index can address — the highest defined id plus one. */
    val size: Int get() = slots.size

    companion object {
        /**
         * Indexes a validated image. Grows by doubling rather than from a constant, for the reason
         * §3.48 records: a hard bound would silently truncate a larger export, and a dropped piece
         * decodes to nothing — a wrong translation with no error attached.
         */
        fun index(image: ByteArray): TargetVocabulary {
            var slots = LongArray(1 shl 17)
            var highest = -1
            var p = VOCAB_HEADER_BYTES
            while (p + 6 <= image.size) {
                val id = readInt(image, p)
                val len = ((image[p + 4].toInt() and 0xFF) shl 8) or (image[p + 5].toInt() and 0xFF)
                val start = p + 6
                if (id >= 0) {
                    if (id >= slots.size) {
                        var size = slots.size
                        while (size <= id) size = size shl 1
                        slots = slots.copyOf(size)
                    }
                    slots[id] = (start.toLong() shl 32) or len.toLong()
                    if (id > highest) highest = id
                }
                p = start + len
            }
            return TargetVocabulary(image, slots.copyOf(highest + 1))
        }

        /** Test/benchmark helper: packs `id to piece` pairs into the same layout the loader uses. */
        fun of(vararg pairs: Pair<Int, String>): TargetVocabulary {
            val out = java.io.ByteArrayOutputStream()
            repeat(VOCAB_HEADER_BYTES) { out.write(0) }
            for ((id, piece) in pairs) {
                val bytes = piece.toByteArray(Charsets.UTF_8)
                out.write(id ushr 24); out.write(id ushr 16); out.write(id ushr 8); out.write(id)
                out.write(bytes.size ushr 8); out.write(bytes.size)
                out.write(bytes)
            }
            return index(out.toByteArray())
        }

        private fun readInt(b: ByteArray, p: Int): Int =
            ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

        /** Mirrors `Tokenizer.VOCAB_HEADER_BYTES`; both describe the same on-disk layout. */
        private const val VOCAB_HEADER_BYTES = 20
    }
}

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
     * id → piece, as an index over the packed vocabulary image — **no `String` is built until one is
     * asked for** (C1, §3.56).
     *
     * §3.48 made this a flat `Array<String?>` indexed by token id, which removed the 516 ms inversion.
     * What it left was 122,672 `String` objects constructed at every cold start to serve the ~12
     * lookups a translation performs. [TargetVocabulary] keeps the bytes and an offset table instead
     * and decodes on demand.
     */
    private val tgtIdToPiece: TargetVocabulary,
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
            .mapNotNull { tgtIdToPiece.pieceAt(it.toInt()) }
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
            // Phase 11A: two marks, so the report can separate the two vocabularies. Inline and
            // debug-gated — release builds are unchanged.
            //
            // Both vocabularies come out of the same packed image (§3.49) and differ only in how they
            // are indexed, which is the whole of C1: `encode` looks pieces up **by string** and needs a
            // hash map, `decode` looks them up **by id** and does not need the strings at all.
            val src = HashMap<String, Int>(1 shl 16)
            forEachEntry(vocabImage(context, srcDict)) { b, off, len, id ->
                src[String(b, off, len, Charsets.UTF_8)] = id
            }
            Metrics.stage("tokenizer:src_dict")
            val tgt = TargetVocabulary.index(vocabImage(context, tgtDict))
            Metrics.stage("tokenizer:tgt_dict")
            val (srcLang, tgtLang) = langIds(direction, src)
            return Tokenizer(src, tgt, srcLang, tgtLang)
        }

        /**
         * Emits every `piece → id` of dictionary [asset], from a **packed binary cache** when one is
         * valid and from the JSON when it is not — writing the cache on the way past (Q4b, §3.49).
         *
         * Why a cache and not a faster parser. `dict.SRC.json` is 0.62 MB and cost **1644 ms**, while
         * `dict.TGT.json` is 3.23 MB and cost 854 ms — five times the bytes in half the time. The
         * difference is not the parsing: it is the ~1.5 s the *first* caller pays running
         * [parseEntries] interpreted, before the JIT compiles a state machine that branches once per
         * character across 4 million characters. No amount of tuning that loop removes a cost that is
         * paid for *having* the loop, and this build cannot AOT it — ART declines to compile a
         * `debuggable` app (§3.29). Reading a packed file is ~157,000 iterations instead of 4,000,000,
         * over a loop simple enough that interpreting it is affordable.
         *
         * Format, deliberately dull: a 16-byte header (magic, version, source length) then entries of
         * `id:int, utf8Length:uint16, bytes` until EOF. **The stamp lives in the header**, not in a
         * companion file, so validity is one atomic artifact — a stamp that can disagree with its data
         * is the failure §3.47 had to add a format token to avoid. A re-exported dictionary changes its
         * length and invalidates the cache; [VOCAB_VERSION] invalidates every cache when this layout
         * changes.
         *
         * Every failure path falls back to the JSON, which is always present in the APK: a corrupt,
         * truncated or unreadable cache costs the launch that hits it a re-parse and nothing else. The
         * cache is written to a `.part` and renamed, so a process killed mid-write cannot leave a
         * half-file that looks complete — the same rule [OnnxModels] uses for extracted assets.
         */
        private fun vocabImage(context: Context, asset: String): ByteArray {
            val cache = File(context.filesDir, "$asset$VOCAB_SUFFIX")
            val sourceLength = dictStamp(context, asset)
            readVocabCache(cache, sourceLength)?.let { return it }

            // Miss. Parse the JSON into the same layout the cache uses, then hand it back — the caller
            // indexes the image, so the parse and the cache write share one representation instead of
            // the parse emitting entries a second way.
            val packed = ByteArrayOutputStream(1 shl 22).apply {
                writeInt(VOCAB_MAGIC); writeInt(VOCAB_VERSION); writeLong(sourceLength); writeInt(0)
            }
            var written = 0
            openDict(context, asset).use { input ->
                parseEntries(bufferedUtf8(input)) { piece, id ->
                    val bytes = piece.toByteArray(Charsets.UTF_8)
                    if (bytes.size <= MAX_PIECE_BYTES) {
                        packed.writeInt(id)
                        packed.write(bytes.size ushr 8); packed.write(bytes.size)
                        packed.write(bytes)
                        written++
                    }
                }
            }
            // The count is only known once the parse ends, so it is patched into the reserved slot
            // rather than streamed. It is what makes a truncation detectable — see [readVocabCache].
            val image = packed.toByteArray()
            writeIntAt(image, VOCAB_COUNT_OFFSET, written)
            writeVocabCache(cache, image)
            return image
        }

        /**
         * Walks a validated image, handing each entry over as `(buffer, offset, length, id)`.
         *
         * The bytes are passed rather than a `String` because that is the entire point of C1: the
         * target vocabulary never needs them decoded, and the source vocabulary decodes them once into
         * a map key. `inline` so neither consumer pays a call per entry across 155,000 entries.
         */
        private inline fun forEachEntry(image: ByteArray, emit: (ByteArray, Int, Int, Int) -> Unit) {
            var p = VOCAB_HEADER_BYTES
            while (p + 6 <= image.size) {
                val id = readInt(image, p)
                val len = ((image[p + 4].toInt() and 0xFF) shl 8) or (image[p + 5].toInt() and 0xFF)
                emit(image, p + 6, len, id)
                p += 6 + len
            }
        }

        /**
         * Reads [cache] and emits its entries, or returns `false` if it is absent, stale or damaged.
         *
         * The whole file is read into one array and walked by index — `DataInputStream` would charge
         * several virtual calls per field, 157,000 times, which is the cost this cache exists to avoid.
         * Bounds are checked before every read rather than trusted: this file is regenerable, so a
         * truncated one must degrade to a re-parse, never to an exception on a user's launch.
         */
        private fun readVocabCache(cache: File, sourceLength: Long): ByteArray? {
            if (!cache.exists()) return null
            return try {
                val b = cache.readBytes()
                if (b.size < VOCAB_HEADER_BYTES) return null
                if (readInt(b, 0) != VOCAB_MAGIC || readInt(b, 4) != VOCAB_VERSION) return null
                if (readLong(b, 8) != sourceLength) return null
                val expected = readInt(b, VOCAB_COUNT_OFFSET)

                // Validated before the caller sees a byte of it: bounds, the entry count, and that
                // the last entry ends exactly at the end of the file. The scan is pointer arithmetic —
                // §3.54's bug was a boundary-aligned truncation being accepted as complete, which the
                // count is what catches.
                var p = VOCAB_HEADER_BYTES
                var seen = 0
                while (p + 6 <= b.size) {
                    val len = ((b[p + 4].toInt() and 0xFF) shl 8) or (b[p + 5].toInt() and 0xFF)
                    val start = p + 6
                    if (start + len > b.size) return null
                    p = start + len
                    seen++
                }
                if (p != b.size || seen != expected) {
                    logWarn(
                        LogTag.MT,
                        "vocabulary cache ${cache.name} is short: $seen of $expected entries, " +
                            "$p of ${b.size} bytes — re-parsing the JSON",
                        null,
                    )
                    return null
                }
                b
            } catch (e: Throwable) {
                logWarn(LogTag.MT, "vocabulary cache unreadable (${cache.name}); re-parsing the JSON", e)
                null
            }
        }

        /** Writes [image] to [cache] via a `.part` rename. A failure only costs the next launch a re-parse. */
        private fun writeVocabCache(cache: File, image: ByteArray) {
            val part = File(cache.parentFile, "${cache.name}.part")
            runCatching {
                part.writeBytes(image)
                cache.delete()
                if (!part.renameTo(cache)) throw java.io.IOException("could not publish ${cache.name}")
                logDebug(LogTag.MT) { "vocabulary cache written: ${cache.name} (${image.size / 1024} KB)" }
            }.onFailure {
                part.delete()
                logWarn(LogTag.MT, "vocabulary cache write failed (${cache.name}); will re-parse next launch", it)
            }
        }

        /**
         * What the cache is stamped against: it has to change whenever the bytes [openDict] would
         * return change, and it has to be readable without decompressing anything.
         *
         * **Not the asset's length.** `AssetManager.openFd` only works on *stored* entries, and these
         * dictionaries ship DEFLATE-compressed (§3.29 measured `noCompress` on them as NO EFFECT and
         * reverted it), so it throws for every one of them. The first version of this cache called it
         * inside a `runCatching` and therefore stamped every dictionary `-1` — a cache that could never
         * go stale, which is the exact failure §3.47 added a format token to prevent. `VocabCacheTest`
         * caught it before it shipped.
         *
         * Install time is the right stamp instead: the vocabularies are packaged assets, so the only
         * way their bytes change is a new APK, and `lastUpdateTime` moves on every install and update.
         * A staged dictionary ([openDict]'s benchapp path) is a real file and is stamped by its own
         * length, in the order [openDict] resolves, so a sideloaded vocabulary is never served from a
         * cache built for the packaged one.
         */
        private fun dictStamp(context: Context, name: String): Long {
            val staged = File(context.filesDir, name)
            if (staged.exists() && staged.length() > 0L) return staged.length()
            return runCatching {
                context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            }.getOrDefault(-1L)
        }

        private fun ByteArrayOutputStream.writeInt(v: Int) {
            write(v ushr 24); write(v ushr 16); write(v ushr 8); write(v)
        }

        private fun ByteArrayOutputStream.writeLong(v: Long) {
            writeInt((v ushr 32).toInt()); writeInt(v.toInt())
        }

        private fun writeIntAt(b: ByteArray, p: Int, v: Int) {
            b[p] = (v ushr 24).toByte(); b[p + 1] = (v ushr 16).toByte()
            b[p + 2] = (v ushr 8).toByte(); b[p + 3] = v.toByte()
        }

        private fun readInt(b: ByteArray, p: Int): Int =
            ((b[p].toInt() and 0xFF) shl 24) or ((b[p + 1].toInt() and 0xFF) shl 16) or
                ((b[p + 2].toInt() and 0xFF) shl 8) or (b[p + 3].toInt() and 0xFF)

        private fun readLong(b: ByteArray, p: Int): Long =
            (readInt(b, p).toLong() and 0xFFFFFFFFL shl 32) or (readInt(b, p + 4).toLong() and 0xFFFFFFFFL)

        /** `"BBV"` + format generation. Bump [VOCAB_VERSION] and every existing cache is rebuilt. */
        private const val VOCAB_MAGIC = 0x42425600
        /**
         * **2, not 1.** Version 1 had no entry count, so a cache truncated *on an entry boundary*
         * walked to the end of the buffer and was accepted as complete — a silently partial
         * vocabulary. `VocabCacheTest` truncated a file to exactly one third, that third landed on a
         * boundary, and the resulting 840 KB target vocabulary was then used by the launches that
         * produced §3.49's timings. Bumping this rebuilds every existing cache.
         */
        private const val VOCAB_VERSION = 2
        private const val VOCAB_COUNT_OFFSET = 16
        private const val VOCAB_HEADER_BYTES = 20
        private const val VOCAB_SUFFIX = ".vocab"

        /**
         * Longest piece the two-byte length field can describe. The largest piece in these
         * vocabularies is a few dozen bytes, so this only bounds a corrupt export — and such an entry
         * is dropped from the *cache* rather than from the map, so the JSON path stays authoritative.
         */
        private const val MAX_PIECE_BYTES = 0xFFFF

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
         * The character-level state machine, used by [parseFlatIntDict] and [vocabImage].
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
