package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.Direction
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Q4b (§3.49): the packed vocabulary cache must be **faster and indistinguishable**, and every way it
 * can be broken must cost a re-parse rather than a launch.
 *
 * The cache is the only piece of startup that persists a derived copy of a shipped asset, so the risk
 * is not that it is slow — it is that a stale, truncated or corrupt file is *believed*. Each of those
 * is written deliberately here and the tokenizer is required to survive it with identical output.
 *
 * Logged under `BB.Q4B`.
 */
@RunWith(AndroidJUnit4::class)
class VocabCacheTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val caches
        get() = listOf("dict.SRC.json.vocab", "dict.TGT.json.vocab").map { File(context.filesDir, it) }

    @Test
    fun cacheIsFasterAndByteIdenticalAndSurvivesCorruption() {
        caches.forEach { it.delete() }

        val (coldMs, cold) = timedLoad()
        assertTrue("the cache must be written on the parse that misses it", caches.all { it.exists() })
        val cacheKb = caches.sumOf { it.length() } / 1024
        // Deliberately NOT assets.openFd: these dictionaries ship DEFLATE-compressed, so openFd
        // throws on them -- the same trap that made the first stamp useless. Count the stream.
        val sourceKb = listOf("dict.SRC.json", "dict.TGT.json")
            .sumOf { name -> context.assets.open(name).use { it.readBytes().size.toLong() } } / 1024

        val (warmMs, warm) = timedLoad()
        Log.i(TAG, "COLD ${coldMs}ms WARM ${warmMs}ms saved=${coldMs - warmMs}ms cache_kb=$cacheKb json_uncompressed_kb=$sourceKb")
        Log.i(TAG, "OUT cold='${cold.decoded}' warm='${warm.decoded}'")

        assertEquals("encode must not change", cold.encoded.toList(), warm.encoded.toList())
        assertEquals("decode must not change", cold.decoded, warm.decoded)
        assertTrue("warm load (${warmMs}ms) must beat the JSON parse (${coldMs}ms)", warmMs < coldMs)

        // 1. Truncated mid-entry — the case a process killed during the write would leave if the
        //    rename were not atomic. Must be detected by the bounds check, not by an exception.
        val truncated = caches[1].readBytes().copyOf(caches[1].length().toInt() / 3)
        caches[1].writeBytes(truncated)
        assertEquals("a truncated cache must fall back to the JSON", cold.decoded, load().decoded)

        // 2. Wrong magic — a foreign or garbage file under the cache's name.
        caches[0].writeBytes(ByteArray(64) { 0x7A })
        assertEquals("a corrupt cache must fall back to the JSON", cold.decoded, load().decoded)

        // 3. Stale stamp — what a re-exported dictionary looks like. The header carries the source
        //    length, so flipping it is exactly the "asset changed" case.
        val header = caches[0].readBytes()
        header[11] = (header[11] + 1).toByte()
        caches[0].writeBytes(header)
        val afterStale = load()
        assertEquals("a stale cache must be rejected, not trusted", cold.decoded, afterStale.decoded)
        assertArrayEquals("and the ids behind it must match too", cold.encoded, afterStale.encoded)

        // Every fallback must also have healed the cache it rejected.
        assertTrue("a rejected cache must be rewritten", caches.all { it.exists() })
        assertEquals("the healed cache must load clean", cold.decoded, load().decoded)
    }

    private fun load(): Loaded {
        val tok = Tokenizer.load(context, Direction.EN_TO_HI)
        val ids = tok.encode(SENTENCE)
        // Round-trips through the target vocabulary, which is the half the id-indexed array replaced.
        return Loaded(ids, tok.decode(longArrayOf(4, 100, 200, 2)))
    }

    private fun timedLoad(): Pair<Long, Loaded> {
        val start = System.nanoTime()
        val loaded = load()
        return (System.nanoTime() - start) / 1_000_000 to loaded
    }

    private class Loaded(val encoded: LongArray, val decoded: String)

    private companion object {
        const val TAG = "BB.Q4B"
        const val SENTENCE = "The weather is very nice today and I want to go outside."
    }
}
