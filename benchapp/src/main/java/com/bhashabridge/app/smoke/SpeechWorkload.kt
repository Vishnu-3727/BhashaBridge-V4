package com.bhashabridge.app.smoke

import android.content.Context
import android.os.Debug
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import com.bhashabridge.app.speech.VoskModels
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Purpose:  The speech half of the benchmark — what the recogniser costs on this phone, and whether
 *           repeating it leaks.
 * Owns:     One [Model] and one [Recognizer] per pass; both released before returning.
 * Lifetime: One run.
 * Thread:   Blocking. Call off the main thread.
 *
 * **Why realtime factor is the headline and latency is not.** A translation's latency is felt
 * directly: the user waits for it. Recognition is not — it runs *while the person is still talking*,
 * so the only question that matters is whether it keeps up. Below 1.0× it does, and the tail after
 * the user stops is all they wait for. Above 1.0× the backlog grows for as long as they keep
 * speaking, and a phone at 1.3× turns a ten-second sentence into three seconds of silence
 * afterwards. That is a cliff, not a gradient, and it is why this phase reports distance from 1.0
 * rather than milliseconds.
 *
 * The audio is a fixture in the APK rather than the microphone. A benchmark that recorded the room
 * would measure the room: comparing two phones requires both to recognise the same waveform, and a
 * device sitting on a desk in a quiet office is not a controlled input, it is an absent one.
 */
class SpeechWorkload(
    private val context: Context,
    private val direction: Direction = Direction.EN_TO_HI,
) {

    class Result(
        val modelLoadMs: Long,
        val audioSeconds: Double,
        val recognition: Stats,
        /** Median ms of CPU per second of audio. Below 1000 the recogniser keeps up with speech. */
        val msPerAudioSecond: Double,
        val transcript: String,
        val decodeConfig: Map<String, String>,
        val leak: LeakResult?,
    ) {
        val realtimeFactor: Double get() = msPerAudioSecond / 1000.0
    }

    /**
     * What repeated sessions did to the process.
     *
     * File descriptors are the load-bearing number. A `Recognizer` that is not closed shows up here
     * as a straight line no allocator can hide, whereas native heap on Android is returned to the OS
     * lazily and will drift either way.
     */
    class LeakResult(
        val cycles: Int,
        val fdDelta: Int,
        val threadDelta: Int,
        val nativeKbDelta: Long,
        val pssKbDelta: Long,
    ) {
        /** One descriptor per cycle is a missed release; a couple in total is the platform. */
        val leaking: Boolean get() = fdDelta > FD_SLACK || threadDelta > THREAD_SLACK
    }

    fun run(iterations: Int, checkLeak: Boolean, onProgress: (String) -> Unit): Result {
        onProgress("staging the acoustic model")
        ModelStore.stageSpeech(context, direction)

        onProgress("loading the acoustic model")
        val models = VoskModels(context)
        val loadStart = System.nanoTime()
        val model = models.model(direction)
        val modelLoadMs = (System.nanoTime() - loadStart) / 1_000_000

        try {
            val pcm = readWavMono16(fixture())
            val audioSeconds = pcm.size.toDouble() / SAMPLE_RATE

            // Untimed: the first pass on a fresh model pays native page-in and JIT that no later one
            // does, and reporting it would make every device look worse than it is in use.
            recognise(model, pcm)

            val samples = ArrayList<Long>(iterations)
            var transcript = ""
            repeat(iterations) { i ->
                onProgress("recognition ${i + 1}/$iterations")
                val (ms, text) = recognise(model, pcm)
                samples += ms
                transcript = text
            }
            val stats = Stats.of(samples)

            val leak = if (checkLeak) {
                onProgress("speech leak churn")
                churn(model, pcm, onProgress)
            } else null

            return Result(
                modelLoadMs = modelLoadMs,
                audioSeconds = audioSeconds,
                recognition = stats,
                msPerAudioSecond = stats.median / audioSeconds,
                transcript = transcript,
                decodeConfig = readDecodeConfig(),
                leak = leak,
            )
        } finally {
            models.release()
        }
    }

    /** One recognition pass, in the same call shape the app's own capture loop uses. */
    private fun recognise(model: Model, pcm: ShortArray): Pair<Long, String> {
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val transcript = StringBuilder()
        val buffer = ShortArray(CHUNK)
        val start = System.nanoTime()
        try {
            var offset = 0
            while (offset < pcm.size) {
                val length = minOf(CHUNK, pcm.size - offset)
                System.arraycopy(pcm, offset, buffer, 0, length)
                if (recognizer.acceptWaveForm(buffer, length)) {
                    text(recognizer.result)?.let {
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(it)
                    }
                } else {
                    // Polled because production polls it — a JNI crossing and a JSON build per
                    // buffer that would otherwise be missing from the measurement.
                    recognizer.partialResult
                }
                offset += length
            }
            text(recognizer.finalResult)?.let {
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(it)
            }
        } finally {
            recognizer.close()
        }
        return (System.nanoTime() - start) / 1_000_000 to transcript.toString().trim()
    }

    /**
     * Repeats the session and reports the drift.
     *
     * The warm-up before the baseline is what separates a leak from a start-up cost: the first passes
     * allocate native scratch and page in the graph, and those never come back. Growth *after* that
     * is the only kind worth reporting.
     */
    private fun churn(model: Model, pcm: ShortArray, onProgress: (String) -> Unit): LeakResult {
        repeat(LEAK_WARMUP) { recognise(model, pcm) }
        val before = probe()
        repeat(LEAK_CYCLES) { i ->
            onProgress("leak churn ${i + 1}/$LEAK_CYCLES")
            recognise(model, pcm)
        }
        val after = probe()
        return LeakResult(
            cycles = LEAK_CYCLES,
            fdDelta = after.fds - before.fds,
            threadDelta = after.threads - before.threads,
            nativeKbDelta = after.nativeKb - before.nativeKb,
            pssKbDelta = after.pssKb - before.pssKb,
        )
    }

    private class Probe(val fds: Int, val threads: Int, val nativeKb: Long, val pssKb: Long)

    private fun probe(): Probe {
        // Collect and settle: the previous cycle's Recognizer is garbage, not a leak, and without
        // this it reads as one.
        Runtime.getRuntime().gc()
        Thread.sleep(SETTLE_MS)
        Runtime.getRuntime().gc()
        Thread.sleep(SETTLE_MS)
        val info = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return Probe(
            fds = File("/proc/self/fd").list()?.size ?: -1,
            threads = Thread.getAllStackTraces().size,
            nativeKb = Debug.getNativeHeapAllocatedSize() / 1024,
            pssKb = info.totalPss.toLong(),
        )
    }

    /**
     * The three decode-width knobs, read back from the model that actually loaded.
     *
     * Reported rather than assumed because they are the recogniser's speed/accuracy dial and they
     * differ per model — the shipped English and Hindi models disagree about all three. A realtime
     * factor quoted without them is not comparable to anything.
     */
    private fun readDecodeConfig(): Map<String, String> {
        val folder = if (direction == Direction.EN_TO_HI) "model" else "model-hi"
        val conf = File(File(context.filesDir, folder), "conf/model.conf")
        if (!conf.isFile) return emptyMap()
        return conf.readLines()
            .mapNotNull { line ->
                val key = INTERESTING.firstOrNull { line.startsWith("--$it=") } ?: return@mapNotNull null
                key to line.substringAfter('=').trim()
            }
            .toMap()
    }

    private fun text(json: String?): String? =
        json?.let { JSONObject(it).optString("text", "").trim() }?.takeIf { it.isNotBlank() }

    private fun fixture(): File {
        val target = File(context.cacheDir, FIXTURE)
        if (!target.isFile || target.length() == 0L) {
            context.assets.open(FIXTURE).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return target
    }

    /**
     * Reads a mono 16-bit PCM WAV by walking the RIFF chunk list. Not a fixed 44-byte skip: encoders
     * insert `LIST`/`fact` chunks before `data`, and a fixed skip feeds those to the recogniser as
     * audio.
     */
    private fun readWavMono16(file: File): ShortArray {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size > 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") {
            "${file.name} is not a RIFF/WAVE file"
        }
        var position = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4)
            val size = buffer.getInt(position + 4)
            val body = position + 8
            when (id) {
                "fmt " -> {
                    channels = buffer.getShort(body + 2).toInt()
                    rate = buffer.getInt(body + 4)
                    bits = buffer.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(channels == 1 && bits == 16 && rate == SAMPLE_RATE) {
                        "fixture must be ${SAMPLE_RATE}Hz mono 16-bit, got ${rate}Hz ${channels}ch ${bits}-bit"
                    }
                    val count = minOf(size, bytes.size - body) / 2
                    val out = ShortArray(count)
                    buffer.position(body)
                    buffer.asShortBuffer().get(out, 0, count)
                    return out
                }
            }
            position = body + size + (size and 1)
        }
        throw IllegalArgumentException("${file.name} has no data chunk")
    }

    companion object {
        const val FIXTURE = "speech_i_need_water.wav"

        private const val SAMPLE_RATE = 16_000

        /** `AudioCapture.BUFFER_SAMPLES`; the phase must feed what production feeds. */
        private const val CHUNK = 4096

        private const val LEAK_WARMUP = 2
        private const val LEAK_CYCLES = 12
        private const val SETTLE_MS = 250L

        const val FD_SLACK = 4
        const val THREAD_SLACK = 2

        private val INTERESTING = listOf("max-active", "beam", "lattice-beam", "min-active")

        /** True when the acoustic model has been sideloaded and this phase can run at all. */
        fun isReady(context: Context, direction: Direction): Boolean =
            ModelStore.isSpeechReady(context, direction)
    }
}
