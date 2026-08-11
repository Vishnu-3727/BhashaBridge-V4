package com.bhashabridge.app.speech

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bhashabridge.app.Direction
import com.bhashabridge.app.bench.Stats
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The scoring harness for Q8 — every recogniser tuning decision is made against these numbers.
 *
 * [SpeechPipelineBenchmarkTest] measures the pipeline once, end to end, which is the right shape for
 * a validation report and the wrong one for tuning: a single sample of Vosk plus MediaCodec plus the
 * translator cannot tell you whether a knob moved the recogniser or the weather. This test isolates
 * Vosk, repeats it, and reports a realtime factor per arm.
 *
 * **Arms.** Each is one `Recognizer` configuration fed the same utterance:
 *  - `16k` — production today: capture at 16 kHz, `Recognizer(model, 16000f)`.
 *  - `8k`  — the same audio downsampled to 8 kHz with `Recognizer(model, 8000f)`.
 *  - `chunk_*` — the production rate at other buffer sizes.
 *
 * The `8k` arm exists because `assets/model/conf/mfcc.conf` reads `--sample-frequency=8000`
 * `--high-freq=3700`: the English model is a telephone-band model, so **every sample above 3.7 kHz
 * that the microphone captures is discarded inside Kaldi**, after this app has paid to record, copy
 * and RMS it, and after Kaldi has paid to resample it away. The arm measures what that costs.
 *
 * **The downsample is deliberately outside the timed region, and that is the point of the
 * comparison, not a flaw in it.** In production nothing in this app would do that work: the
 * microphone path would ask `AudioRecord` for 8 kHz and the platform's own resampler — which is
 * running anyway, because the hardware captures at 48 kHz regardless — would deliver it. So the
 * timed region is the honest one: what the app would actually still be paying for.
 *
 * Accuracy is guarded, not measured. The fixture is synthesised speech, so a word-error rate over it
 * would describe one synthetic voice; what matters for a tuning decision is that an arm did not
 * *change* what was heard, which is what [assertSameTranscript] checks.
 */
@RunWith(AndroidJUnit4::class)
class AsrTuningBenchmarkTest {

    @Test
    fun sweepsRecogniserConfigurations() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pcm16k = readWavMono16(copyFixture(context))
        val audioSeconds = pcm16k.size.toDouble() / FIXTURE_RATE
        // Untimed, and good quality on purpose: a naive drop-every-other-sample decimation folds
        // everything above 4 kHz back into the band as alias noise, which would hand the 8k arm a
        // WER penalty the real microphone path never pays.
        val pcm8k = downsampleHalf(pcm16k)

        Log.i(TAG, "FIXTURE samples=${pcm16k.size} seconds=${round2(audioSeconds)}")

        val models = VoskModels(context)
        try {
            val model = models.model(Direction.EN_TO_HI)
            val arms = listOf(
                Arm("16k", pcm16k, FIXTURE_RATE, DEFAULT_CHUNK),
                Arm("8k", pcm8k, HALF_RATE, DEFAULT_CHUNK / 2),
                Arm("16k_chunk1024", pcm16k, FIXTURE_RATE, 1024),
                Arm("16k_chunk8192", pcm16k, FIXTURE_RATE, 8192),
            )

            val results = arms.map { arm ->
                // One untimed pass per arm: the first Recognizer on a fresh model pays JIT and
                // native page-in that no later one does, and it is not what production sees.
                run(model, arm)
                val samples = ArrayList<Long>(ITERATIONS)
                var transcript = ""
                repeat(ITERATIONS) {
                    val (ms, text) = run(model, arm)
                    samples += ms
                    transcript = text
                }
                val stats = Stats.of(samples)
                // ms of CPU per second of audio. Below 1000 the recogniser keeps up with live
                // speech; above it, a live session falls further behind the longer the user talks.
                val perAudioSecond = stats.median / audioSeconds
                Log.i(
                    TAG,
                    "ARM ${arm.name} rate=${arm.rate} chunk=${arm.chunk} " +
                        "median=${round2(stats.median)}ms p95=${round2(stats.p95)}ms " +
                        "stdev=${round2(stats.stdev)} realtime=${round2(perAudioSecond / 1000.0)}x " +
                        "ms_per_audio_s=${round2(perAudioSecond)} transcript=\"$transcript\"",
                )
                Log.i(TAG, "ARM_JSON ${arm.name} ${stats.toJson()}")
                arm.name to transcript
            }

            val baseline = results.first()
            results.drop(1).forEach { assertSameTranscript(baseline, it) }
            assertTrue("empty transcript from the baseline arm", baseline.second.isNotBlank())
        } finally {
            models.release()
        }
    }

    private class Arm(val name: String, val pcm: ShortArray, val rate: Int, val chunk: Int)

    /**
     * One recognition pass: a fresh `Recognizer`, the whole utterance fed chunk by chunk, then the
     * final flush — the same call sequence [AudioCapture] and [AudioFileTranscriber] both make.
     *
     * `partialResult` is polled on every non-final chunk because production does: it is a JNI
     * crossing plus a JSON build per buffer, so leaving it out would measure a recogniser this app
     * does not run.
     */
    private fun run(model: Model, arm: Arm): Pair<Long, String> {
        val recognizer = Recognizer(model, arm.rate.toFloat())
        val transcript = StringBuilder()
        val buffer = ShortArray(arm.chunk)
        val start = System.nanoTime()
        try {
            var offset = 0
            while (offset < arm.pcm.size) {
                val length = minOf(arm.chunk, arm.pcm.size - offset)
                System.arraycopy(arm.pcm, offset, buffer, 0, length)
                if (recognizer.acceptWaveForm(buffer, length)) {
                    text(recognizer.result, "text")?.let {
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(it)
                    }
                } else {
                    text(recognizer.partialResult, "partial")
                }
                offset += length
            }
            text(recognizer.finalResult, "text")?.let {
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(it)
            }
        } finally {
            recognizer.close()
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        return ms to transcript.toString().trim()
    }

    /**
     * A tuning arm may be faster. It may not quietly hear something else — that trade is a product
     * decision, not one a benchmark gets to make silently, so a changed transcript fails the run and
     * prints both so the difference is visible rather than inferred.
     */
    private fun assertSameTranscript(baseline: Pair<String, String>, arm: Pair<String, String>) {
        assertTrue(
            "arm ${arm.first} changed the transcript:\n" +
                "  ${baseline.first}: \"${baseline.second}\"\n" +
                "  ${arm.first}: \"${arm.second}\"",
            baseline.second == arm.second,
        )
    }

    private fun text(json: String?, key: String): String? =
        json?.let { JSONObject(it).optString(key, "").trim() }?.takeIf { it.isNotBlank() }

    /**
     * Reads a mono 16-bit PCM WAV into samples by walking the RIFF chunk list.
     *
     * Not a fixed 44-byte header skip: encoders insert `LIST`/`fact` chunks before `data`, and a
     * fixed skip silently feeds those bytes to the recogniser as audio. Only the one format the
     * fixture uses is accepted — anything else fails loudly rather than being misread as samples.
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
                    require(channels == 1 && bits == 16 && rate == FIXTURE_RATE) {
                        "fixture must be ${FIXTURE_RATE}Hz mono 16-bit, got ${rate}Hz ${channels}ch ${bits}-bit"
                    }
                    val count = minOf(size, bytes.size - body) / 2
                    val out = ShortArray(count)
                    buffer.position(body)
                    buffer.asShortBuffer().get(out, 0, count)
                    return out
                }
            }
            // Chunk bodies are word-aligned: an odd size carries a pad byte that is not counted.
            position = body + size + (size and 1)
        }
        throw IllegalArgumentException("${file.name} has no data chunk")
    }

    /**
     * Exact 2:1 decimation through a windowed-sinc low-pass at the new Nyquist.
     *
     * A Blackman-windowed sinc of [FILTER_HALF] taps per side, which is the same shape Kaldi's own
     * `LinearResample` uses and well past the point where stopband leakage could colour the
     * comparison. Runs once per test, outside every timed region.
     */
    private fun downsampleHalf(input: ShortArray): ShortArray {
        val taps = DoubleArray(2 * FILTER_HALF + 1) { i ->
            val n = i - FILTER_HALF
            // Cutoff at 0.5 of the input Nyquist = the output Nyquist; sinc(0) taken as its limit.
            val sinc = if (n == 0) 0.5 else sin(PI * 0.5 * n) / (PI * n)
            val w = 0.42 - 0.5 * cos(2 * PI * i / (2.0 * FILTER_HALF)) +
                0.08 * cos(4 * PI * i / (2.0 * FILTER_HALF))
            sinc * w
        }
        val gain = taps.sum()
        return ShortArray(input.size / 2) { out ->
            val centre = out * 2
            var acc = 0.0
            for (i in taps.indices) {
                val src = centre + i - FILTER_HALF
                if (src in input.indices) acc += input[src] * taps[i]
            }
            (acc / gain).coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    private fun copyFixture(context: Context): File {
        val target = File(context.cacheDir, FIXTURE)
        InstrumentationRegistry.getInstrumentation().context.assets.open(FIXTURE).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private companion object {
        const val FIXTURE = "speech_i_need_water.wav"
        const val TAG = "BB_ASR_TUNE"
        const val FIXTURE_RATE = 16_000
        const val HALF_RATE = 8_000

        /** `AudioCapture.BUFFER_SAMPLES` — the arms must start from what production actually feeds. */
        const val DEFAULT_CHUNK = 4096
        const val ITERATIONS = 15
        const val FILTER_HALF = 32
    }
}
