package com.bhashabridge.app.speech

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteOrder

/**
 * Purpose:  Transcribes an audio file the user picked, by replaying it through Vosk.
 * Owns:     A `MediaCodec`, a `MediaExtractor` and one `Recognizer`, all per call.
 * Lifetime: One [transcribe] collection.
 * Thread:   Decode and recognition run on [Dispatchers.IO].
 *
 * Emits the same [SpeechEvent] shape as live capture, so the ViewModel treats an imported file and
 * a spoken utterance identically — one code path, not two. Amplitude is not emitted: there is no
 * live signal to visualise.
 */
object AudioFileTranscriber {

    private const val TARGET_SAMPLE_RATE = 16_000
    private const val MAX_DURATION_MS = 60_000L
    private const val CHUNK = 4096

    /**
     * Decodes [uri] to 16 kHz mono PCM, then feeds it to Vosk in fixed-size chunks — the same
     * buffer-at-a-time shape the microphone loop uses, just from memory.
     */
    fun transcribe(context: Context, uri: Uri, model: Model): Flow<SpeechEvent> = flow {
        val pcm = decodeToPcm(context, uri)
        if (pcm == null || pcm.isEmpty()) {
            emit(SpeechEvent.NoSpeech)
            return@flow
        }
        val recognizer = Recognizer(model, TARGET_SAMPLE_RATE.toFloat())
        val transcript = StringBuilder()
        try {
            var offset = 0
            while (offset < pcm.size) {
                currentCoroutineContext().ensureActive()
                val end = minOf(offset + CHUNK, pcm.size)
                val chunk = pcm.copyOfRange(offset, end)
                if (recognizer.acceptWaveForm(chunk, chunk.size)) {
                    text(recognizer.result, "text")?.let { sentence ->
                        if (transcript.isNotEmpty()) transcript.append(' ')
                        transcript.append(sentence)
                        emit(SpeechEvent.Partial(transcript.toString()))
                    }
                } else {
                    text(recognizer.partialResult, "partial")?.let { partial ->
                        emit(
                            SpeechEvent.Partial(
                                if (transcript.isEmpty()) partial else "$transcript $partial"
                            )
                        )
                    }
                }
                offset = end
            }
            text(recognizer.finalResult, "text")?.let { tail ->
                if (transcript.isNotEmpty()) transcript.append(' ')
                transcript.append(tail)
            }
            val result = transcript.toString().trim()
            emit(if (result.isNotEmpty()) SpeechEvent.Final(result) else SpeechEvent.NoSpeech)
        } finally {
            recognizer.close()
        }
    }.flowOn(Dispatchers.IO)

    private fun text(json: String?, key: String): String? =
        json?.let { JSONObject(it).optString(key, "").trim() }?.takeIf { it.isNotBlank() }

    /**
     * Demuxes the container, decodes the first audio track, downmixes to mono and resamples to
     * 16 kHz — what Vosk requires. There are no per-format branches because `MediaCodec` selects
     * the decoder from the track's MIME type, so MP3/M4A/OGG/WAV all take the same path.
     *
     * Capped at [MAX_DURATION_MS]: the whole file is decoded into memory before recognition
     * starts, and this is a phrase-translation app, not a transcription service.
     */
    private fun decodeToPcm(context: Context, uri: Uri): ShortArray? = try {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
            extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        val format = trackIndex?.let { extractor.getTrackFormat(it) }
        when {
            format == null -> {
                logError(LogTag.SPEECH, "No audio track in the selected file")
                extractor.release()
                null
            }
            format.durationMs() > MAX_DURATION_MS -> {
                logError(LogTag.SPEECH, "Audio too long: ${format.durationMs() / 1000}s")
                extractor.release()
                null
            }
            else -> {
                extractor.selectTrack(trackIndex)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val samples = decodeTrack(extractor, format)
                extractor.release()
                resample(downmix(samples, channels), sampleRate, TARGET_SAMPLE_RATE)
                    .also { logDebug(LogTag.SPEECH) { "Decoded ${it.size} samples at 16 kHz" } }
            }
        }
    } catch (e: Exception) {
        logError(LogTag.SPEECH, "Audio decode failed", e)
        null
    }

    private fun MediaFormat.durationMs(): Long =
        if (containsKey(MediaFormat.KEY_DURATION)) getLong(MediaFormat.KEY_DURATION) / 1000 else 0L

    /** The standard MediaCodec queue loop: fill an input buffer, drain an output buffer, repeat. */
    private fun decodeTrack(extractor: MediaExtractor, format: MediaFormat): ShortArray {
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        val out = ArrayList<Short>(1 shl 16)
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        try {
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                    val shorts = buffer.asShortBuffer()
                    while (shorts.hasRemaining()) out.add(shorts.get())
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
        } finally {
            codec.stop()
            codec.release()
        }
        return ShortArray(out.size) { out[it] }
    }

    /** Plain channel average. Not frequency-aware, which speech recognition tolerates. */
    private fun downmix(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        return ShortArray(samples.size / channels) { i ->
            var sum = 0L
            for (c in 0 until channels) sum += samples[i * channels + c]
            (sum / channels).toShort()
        }
    }

    /** Linear interpolation, no anti-aliasing filter — adequate for a recogniser's input. */
    private fun resample(input: ShortArray, srcRate: Int, dstRate: Int): ShortArray {
        if (srcRate == dstRate || input.isEmpty()) return input
        val ratio = srcRate.toDouble() / dstRate
        return ShortArray((input.size / ratio).toInt()) { i ->
            val position = i * ratio
            val index = position.toInt().coerceIn(0, input.size - 1)
            val fraction = position - index
            val a = input[index].toDouble()
            val b = input[(index + 1).coerceIn(0, input.size - 1)].toDouble()
            (a + fraction * (b - a)).toInt().toShort()
        }
    }

    private const val TIMEOUT_US = 10_000L
}
