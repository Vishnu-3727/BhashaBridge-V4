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

    /**
     * Q8a: what the Hindi model's decode width costs, and whether narrowing it changes what is heard.
     *
     * The two shipped models disagree about how wide to search. English carries the small-model fast
     * configuration; Hindi carries the wide one, over a graph 2.4× larger (55.9 MB of FST against
     * 34.4 MB). Hindi is therefore the arm that can plausibly fail to keep up on a slower phone than
     * this one, and until now it had never been timed at all.
     *
     * `max-active`, `beam` and `lattice-beam` are read out of `conf/model.conf` when the `Model` is
     * constructed, and [VoskModels] unpacks that file to `filesDir`. So the sweep rewrites the
     * unpacked copy and rebuilds the model per arm — the only way to vary these without shipping a
     * second copy of a 56 MB graph. **The original text is restored in a `finally`**: this file
     * belongs to the installed app, and leaving an arm's configuration behind would silently retune
     * the real thing.
     */
    @Test
    fun sweepsHindiDecodeConfiguration() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val pcm = readWavMono16(copyFixture(context, HINDI_FIXTURE))
        val audioSeconds = pcm.size.toDouble() / FIXTURE_RATE
        Log.i(TAG, "HINDI_FIXTURE samples=${pcm.size} seconds=${round2(audioSeconds)}")

        // Clean synthesised speech is the one condition a narrowed beam is guaranteed to survive:
        // the correct path stays inside any beam when nothing competes with it. Two noise levels are
        // included so the accuracy question is asked where it can actually be answered — at 10 dB the
        // recogniser is working, at 5 dB it is struggling, and a beam too narrow to hold the right
        // hypothesis will drop it there long before it does on the clean take.
        val conditions = listOf(
            "clean" to pcm,
            "noise10db" to withNoise(pcm, snrDb = 10.0),
            "noise5db" to withNoise(pcm, snrDb = 5.0),
        )

        val modelDir = AssetFolder.unpack(context, HINDI_MODEL_FOLDER)
        val conf = File(modelDir, "conf/model.conf")
        val original = conf.readText()
        // condition -> (config -> transcript), so accuracy is compared between configs *within* a
        // condition. Comparing across conditions would only rediscover that noise hurts.
        val heard = LinkedHashMap<String, LinkedHashMap<String, String>>()
        try {
            for ((name, text) in hindiArms(original)) {
                conf.writeText(text)
                val loadStart = System.nanoTime()
                val model = Model(modelDir)
                val loadMs = (System.nanoTime() - loadStart) / 1_000_000
                try {
                    for ((condition, audio) in conditions) {
                        val arm = Arm(name, audio, FIXTURE_RATE, DEFAULT_CHUNK)
                        run(model, arm)
                        val samples = ArrayList<Long>(ITERATIONS)
                        var transcript = ""
                        repeat(ITERATIONS) {
                            val (ms, text2) = run(model, arm)
                            samples += ms
                            transcript = text2
                        }
                        val stats = Stats.of(samples)
                        val perAudioSecond = stats.median / audioSeconds
                        Log.i(
                            TAG,
                            "HI_ARM $name/$condition load=${loadMs}ms median=${round2(stats.median)}ms " +
                                "p95=${round2(stats.p95)}ms stdev=${round2(stats.stdev)} " +
                                "realtime=${round2(perAudioSecond / 1000.0)}x " +
                                "ms_per_audio_s=${round2(perAudioSecond)} transcript=\"$transcript\"",
                        )
                        Log.i(TAG, "HI_ARM_JSON $name/$condition ${stats.toJson()}")
                        heard.getOrPut(condition) { LinkedHashMap() }[name] = transcript
                    }
                } finally {
                    model.close()
                }
            }
        } finally {
            conf.writeText(original)
        }

        // Reported, never asserted. A narrower beam is *allowed* to change the hypothesis — that is
        // the trade being priced, and a benchmark does not get to fail a run over a product
        // decision. What it must do is make the difference impossible to miss.
        heard.forEach { (condition, byConfig) ->
            val baseline = byConfig[STOCK] ?: return@forEach
            byConfig.forEach { (config, transcript) ->
                if (config != STOCK) {
                    val verdict = if (transcript == baseline) "SAME" else "CHANGED"
                    Log.i(TAG, "HI_ACCURACY $condition $config $verdict")
                    if (transcript != baseline) {
                        Log.i(TAG, "HI_ACCURACY   $STOCK: \"$baseline\"")
                        Log.i(TAG, "HI_ACCURACY   $config: \"$transcript\"")
                    }
                }
            }
        }
        assertTrue("the stock Hindi arm heard nothing", heard["clean"]?.get(STOCK).isNullOrBlank().not())
    }

    /**
     * Adds white Gaussian noise at a given SNR, with a fixed seed so every arm and every re-run gets
     * the *same* noise — otherwise a transcript difference between configs could be the noise, and
     * the comparison would prove nothing. Untimed, like the resampling in the other test.
     */
    private fun withNoise(input: ShortArray, snrDb: Double): ShortArray {
        var power = 0.0
        for (s in input) power += s.toDouble() * s
        val signalRms = kotlin.math.sqrt(power / input.size)
        val noiseRms = signalRms / Math.pow(10.0, snrDb / 20.0)
        val random = java.util.Random(NOISE_SEED)
        return ShortArray(input.size) { i ->
            (input[i] + random.nextGaussian() * noiseRms)
                .coerceIn(-32768.0, 32767.0).toInt().toShort()
        }
    }

    /**
     * The stock text with the three decode-width knobs rewritten; every other line — the endpointer
     * rules, the acoustic scale, the subsampling factor — is carried through untouched, so an arm
     * differs from the baseline in exactly the three values under test.
     */
    private fun hindiArms(original: String): List<Pair<String, String>> = listOf(
        STOCK to original,
        "hi_english_config" to retune(original, maxActive = 3000, beam = 10.0, latticeBeam = 2.0),
        "hi_mid" to retune(original, maxActive = 5000, beam = 11.5, latticeBeam = 3.0),
        // Counterbalance: the stock arm again, last and hottest. If it reads like the first stock
        // arm the device did not drift and the middle arms can be believed.
        "hi_stock_recheck" to original,
    )

    private fun retune(original: String, maxActive: Int, beam: Double, latticeBeam: Double): String =
        original.lineSequence().joinToString("\n") { line ->
            when {
                line.startsWith("--max-active=") -> "--max-active=$maxActive"
                line.startsWith("--beam=") -> "--beam=$beam"
                line.startsWith("--lattice-beam=") -> "--lattice-beam=$latticeBeam"
                else -> line
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

    private fun copyFixture(context: Context, name: String = FIXTURE): File {
        val target = File(context.cacheDir, name)
        InstrumentationRegistry.getInstrumentation().context.assets.open(name).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    private companion object {
        const val FIXTURE = "speech_i_need_water.wav"

        /**
         * `"मुझे पानी चाहिए। कृपया मेरी मदद कीजिए।"`, synthesised by the device's Google TTS Hindi
         * voice, resampled to 16 kHz mono and silence-trimmed on the host. Synthetic for the same
         * reason the English fixture is: it makes the comparison repeatable. It is not a WER corpus
         * and no entry may quote one from it.
         */
        const val HINDI_FIXTURE = "speech_hi_paani.wav"
        const val HINDI_MODEL_FOLDER = "model-hi"
        const val STOCK = "hi_stock"

        /** Fixed, so the noise is a constant of the experiment rather than a variable in it. */
        const val NOISE_SEED = 20260811L
        const val TAG = "BB_ASR_TUNE"
        const val FIXTURE_RATE = 16_000
        const val HALF_RATE = 8_000

        /** `AudioCapture.BUFFER_SAMPLES` — the arms must start from what production actually feeds. */
        const val DEFAULT_CHUNK = 4096
        const val ITERATIONS = 15
        const val FILTER_HALF = 32
    }
}
