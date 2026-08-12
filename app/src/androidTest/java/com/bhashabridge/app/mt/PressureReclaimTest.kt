package com.bhashabridge.app.mt

import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Queue item Q15: is file-backed actually the better kind of memory here?
 *
 * §3.27 moved 451 MB of weights from anonymous heap to file-backed mappings and left the decision
 * open, because the argument for it — "clean pages can be dropped, anonymous pages can only be
 * swapped or killed for" — was reasoning, not measurement. On this device it is also **partly
 * wrong**: the M31 has 6 GB of zram (5.1 GB free), so anonymous pages are not unswappable. They are
 * compressed. The real comparison is therefore *drop-and-re-read from flash* against
 * *compress-and-decompress in zram*, and which one costs more when the pages are needed again.
 *
 * The test applies real pressure — touched direct allocations, in 128 MB steps — and watches, per
 * step: how much of the model mapping is still resident, what the system has left, how much has
 * gone to swap, and what a translation costs at that moment.
 *
 * Each arm runs in its own process (one `am instrument` invocation per test method), because the
 * whole point is to let the kernel reclaim from a process holding one arm's memory layout.
 *
 * Logged under `BB.Q15`.
 */
@RunWith(AndroidJUnit4::class)
class PressureReclaimTest {

    private val app get() = ApplicationProvider.getApplicationContext<BhashaBridgeApp>()

    /**
     * The shipping configuration under pressure. Q21 (§3.47) collapsed this test's original two arms
     * into one: the A/B was ORT-format initializer-copying against a mapped buffer, and neither exists
     * now — the baked artifact is optimized ONNX whose weights ORT maps from the shared blob, so every
     * install is the file-backed arm. What is still worth watching is unchanged, and is what this
     * measures: how much stays resident, what goes to swap, and what a translation costs while it does.
     */
    @Test
    fun shippingConfigUnderPressure() = runArm("shipping", ExecutionPolicy.current)

    private fun runArm(label: String, tune: OrtTuning) {
        val engine = MtEngine(app, Direction.EN_TO_HI, tune = tune)
        repeat(2) { engine.translate(SENTENCE) }
        sample(label, "baseline", engine)

        val hogs = ArrayList<android.os.MemoryFile>()
        try {
            var allocatedMb = 0
            while (allocatedMb < MAX_PRESSURE_MB) {
                try {
                    hogs += anonymousPressure(CHUNK_MB)
                    allocatedMb += CHUNK_MB
                } catch (e: Throwable) {
                    Log.i(TAG, "REPORT $label allocation_failed_at_mb=$allocatedMb cause=${e::class.java.simpleName}")
                    break
                }
                if (allocatedMb % SAMPLE_EVERY_MB == 0) sample(label, "pressure_${allocatedMb}mb", engine)
            }
            sample(label, "peak_pressure", engine)
        } finally {
            hogs.forEach { runCatching { it.close() } }
            hogs.clear()
            System.gc()
            Thread.sleep(3_000)
            sample(label, "released", engine)
            engine.release()
        }
    }

    /**
     * [sizeMb] of resident, non-reclaimable-for-free memory, outside the VM's accounting.
     *
     * Two mechanisms were tried and rejected before this one, which is worth recording because both
     * looked obviously correct:
     *
     *  - `ByteBuffer.allocateDirect` — Android accounts direct buffers against the VM limit, so a
     *    128 MB request threw `OutOfMemoryError` while the device still had 2 GB available. It
     *    measured the allocator's policy, not the kernel's.
     *  - A **private mapping of `/dev/zero`** — `FileChannel.map` refuses it with `IOException`; it
     *    is a character device with no length to map.
     *
     * `MemoryFile` is ashmem: real pages, pinned by default, charged to this process but not to the
     * Java heap. Writing a megabyte at a time touches every page in it.
     */
    private fun anonymousPressure(sizeMb: Int): android.os.MemoryFile {
        val file = android.os.MemoryFile("bb-pressure-${System.nanoTime()}", sizeMb shl 20)
        val block = ByteArray(1 shl 20) { 1 }
        var written = 0
        while (written < (sizeMb shl 20)) {
            file.writeBytes(block, 0, written, block.size)
            written += block.size
        }
        return file
    }

    /** One line per observation: what is still resident, what the system has, what a translation costs. */
    private fun sample(label: String, stage: String, engine: MtEngine) {
        val ortRss = ortMappingRssKb()
        val mem = meminfo()
        val t = System.nanoTime()
        val ok = runCatching { engine.translate(SENTENCE) }.isSuccess
        val translateMs = (System.nanoTime() - t) / 1_000_000
        Log.i(
            TAG,
            "REPORT $label $stage ort_rss_kb=$ortRss" +
                " mem_available_kb=${mem["MemAvailable"]}" +
                " swap_free_kb=${mem["SwapFree"]}" +
                " swap_used_kb=${(mem["SwapTotal"] ?: 0) - (mem["SwapFree"] ?: 0)}" +
                " translate_ms=$translateMs ok=$ok",
        )
    }

    /**
     * Resident kilobytes of the model mappings, from `/proc/self/smaps`.
     *
     * Q21 (§3.47) changed which file that is: the weights now live in the shared `weights.bin` blob
     * the optimized graphs point at, not in a `.ort` flatbuffer, so the match covers both and the
     * measurement means the same thing across the change.
     *
     * This is the number the whole question turns on: mapped bytes are address space, resident bytes
     * are what the kernel would have to reclaim. If file-backed pages are being dropped under
     * pressure, this falls while the mapping itself stays.
     */
    private fun ortMappingRssKb(): Long = runCatching {
        var total = 0L
        var inOrtMapping = false
        File("/proc/self/smaps").forEachLine { line ->
            when {
                line.contains("-") && line.contains(" ") && line.count { it == ' ' } >= 5 &&
                    !line.startsWith("Rss") && line.substringBefore('-').length >= 8 -> {
                    inOrtMapping = line.contains("/com.bhashabridge") &&
                        (line.contains(".ort") || line.contains(".onnx") || line.contains(".bin"))
                }
                inOrtMapping && line.startsWith("Rss:") -> {
                    total += line.filter { it.isDigit() }.toLongOrNull() ?: 0L
                }
            }
        }
        total
    }.getOrDefault(-1L)

    private fun meminfo(): Map<String, Long> = runCatching {
        File("/proc/meminfo").readLines().associate { line ->
            val key = line.substringBefore(':').trim()
            val value = line.filter { it.isDigit() }.toLongOrNull() ?: 0L
            key to value
        }
    }.getOrDefault(emptyMap())

    private companion object {
        const val TAG = "BB.Q15"
        const val SENTENCE = "The weather is very nice today and I want to go outside."
        const val CHUNK_MB = 128
        const val SAMPLE_EVERY_MB = 512
        /** Bounded: enough to force reclaim on a 5.7 GB device with 2.2 GB available, not enough to wedge it. */
        const val MAX_PRESSURE_MB = 3_072
    }
}
