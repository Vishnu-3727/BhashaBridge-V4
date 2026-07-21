package com.bhashabridge.app.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logWarn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.sqrt

/** What one recording session reports as it runs. */
sealed interface SpeechEvent {
    /** Live loudness, 0..1, one per captured buffer — drives the waveform. */
    data class Amplitude(val level: Float) : SpeechEvent
    /** Interim text: still changing, safe to show, not safe to commit. */
    data class Partial(val text: String) : SpeechEvent
    /** The utterance, once the user stops. Terminal. */
    data class Final(val text: String) : SpeechEvent
    /** The user stopped without saying anything recognisable. Terminal. */
    data object NoSpeech : SpeechEvent
}

/**
 * Purpose:  One microphone recording session, exposed as a [Flow] of [SpeechEvent].
 * Owns:     An `AudioRecord`, its three platform effects, and one Vosk `Recognizer` — all created
 *           when collection starts and released when it ends, by any route.
 * Lifetime: One session per [record] collection.
 * Thread:   Runs on [Dispatchers.IO]; the collector sees events on its own dispatcher.
 *
 * Capture is manual rather than Vosk's own `SpeechService` because the UI needs live amplitude for
 * the waveform and interim text for streaming translation, and that callback shape provides
 * neither.
 *
 * Stopping is [stop], not cancellation: the final Vosk flush has to happen *after* the user stops,
 * and a cancelled coroutine cannot emit. Cancellation still works — it just abandons the session
 * instead of finishing it, and the `finally` block releases the hardware either way. v3.4.1 spread
 * this over an executor, a handler, three callbacks and a `stopAndFlush` continuation.
 */
class AudioCapture(private val sampleRate: Int = SAMPLE_RATE) {

    @Volatile private var recording = false

    /** Ends the session cleanly: the flow emits [SpeechEvent.Final] (or [SpeechEvent.NoSpeech]) and completes. */
    fun stop() {
        recording = false
    }

    /**
     * Records until [stop]. The caller must hold RECORD_AUDIO — without it `AudioRecord` yields
     * silence rather than throwing, so permission is checked before this is ever collected.
     */
    @SuppressLint("MissingPermission")
    fun record(model: Model): Flow<SpeechEvent> = flow {
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        val effects = Effects.attach(recorder.audioSessionId)
        val recognizer = Recognizer(model, sampleRate.toFloat())

        recording = true
        var lastCompleted = ""
        try {
            recorder.startRecording()
            val buffer = ShortArray(BUFFER_SAMPLES)
            while (recording && currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                emit(SpeechEvent.Amplitude((rms(buffer, read) * AMPLITUDE_GAIN).coerceIn(0f, 1f)))
                // acceptWaveForm() true = Vosk closed a sentence; false = utterance still open.
                if (recognizer.acceptWaveForm(buffer, read)) {
                    text(recognizer.result, "text")?.let {
                        lastCompleted = it
                        emit(SpeechEvent.Partial(it))
                    }
                } else {
                    text(recognizer.partialResult, "partial")?.let { emit(SpeechEvent.Partial(it)) }
                }
            }

            // Flush. Vosk may have nothing finalised if the user stopped mid-word, so fall back
            // through the partial and then the last completed sub-phrase before giving up.
            val final = text(recognizer.finalResult, "text")
                ?: text(recognizer.partialResult, "partial")
                ?: lastCompleted.takeIf { it.isNotBlank() }
            emit(if (final != null) SpeechEvent.Final(final) else SpeechEvent.NoSpeech)
        } finally {
            recording = false
            runCatching { recorder.stop() }
            recorder.release()
            effects.release()
            recognizer.close()
            logDebug(LogTag.SPEECH) { "Recording session closed" }
        }
    }.flowOn(Dispatchers.IO)

    /** Reads [key] out of a Vosk JSON result, or null if it is absent/blank. */
    private fun text(json: String?, key: String): String? =
        json?.let { JSONObject(it).optString(key, "").trim() }?.takeIf { it.isNotBlank() }

    /** The three platform audio effects, attached when available and always released together. */
    private class Effects(
        private val noise: NoiseSuppressor?,
        private val echo: AcousticEchoCanceler?,
        private val gain: AutomaticGainControl?,
    ) {
        fun release() {
            noise?.release(); echo?.release(); gain?.release()
        }

        companion object {
            fun attach(sessionId: Int) = Effects(
                noise = create { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId) else null },
                echo = create { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId) else null },
                gain = create { if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId) else null },
            ).also { it.noise?.enabled = true; it.echo?.enabled = true; it.gain?.enabled = true }

            /** An unavailable or vendor-broken effect degrades capture quality; it never fails it. */
            private fun <T> create(block: () -> T?): T? = try {
                block()
            } catch (e: Exception) {
                logWarn(LogTag.SPEECH, "Audio effect unavailable; capture continues without it", e)
                null
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        private const val BUFFER_SAMPLES = 4096

        /** v3.4.1's factor: raw speech RMS sits near the bottom of 0..1, so the bars barely moved. */
        private const val AMPLITUDE_GAIN = 5f

        /**
         * RMS of signed 16-bit PCM, normalised to 0..1. A loudness estimate, not a peak detector —
         * one spike in a quiet buffer barely moves it, which is what keeps the waveform smooth.
         */
        fun rms(buffer: ShortArray, read: Int): Float {
            if (read <= 0) return 0f
            var sum = 0.0
            for (i in 0 until read) sum += buffer[i].toDouble() * buffer[i]
            return (sqrt(sum / read) / 32768.0).toFloat().coerceIn(0f, 1f)
        }
    }
}
