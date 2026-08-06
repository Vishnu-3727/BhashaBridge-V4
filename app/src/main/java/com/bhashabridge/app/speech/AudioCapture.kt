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
import com.bhashabridge.app.logError
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
import java.util.concurrent.atomic.AtomicInteger
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

    /**
     * Sessions are numbered so a stop can never be lost to a start that has not happened yet.
     *
     * A `@Volatile Boolean` could not express that. `stop()` set it false and [record] set it true
     * **inside the flow builder** — which runs only once collection begins, after the model load,
     * the state updates and a dispatcher hop. A stop arriving in that window was overwritten by the
     * start it was meant to cancel, and the microphone stayed live until the user stopped it a
     * second time. `MainActivity.onStop` is one of those callers: leave the app while the model is
     * still loading and the recording outlived the visible screen, which is exactly what that call
     * exists to prevent.
     *
     * [stopRequested] records the newest session number that has been asked to stop, so a stop
     * issued before a session starts still applies to it.
     */
    private val generation = AtomicInteger(0)
    private val stopRequested = AtomicInteger(0)

    /** Ends the session cleanly: the flow emits [SpeechEvent.Final] (or [SpeechEvent.NoSpeech]) and completes. */
    fun stop() {
        // The session about to start counts as stopped too: claim the next number, not the current.
        stopRequested.set(generation.get() + 1)
    }

    /**
     * Records until [stop]. The caller must hold RECORD_AUDIO — without it `AudioRecord` yields
     * silence rather than throwing, so permission is checked before this is ever collected.
     */
    @SuppressLint("MissingPermission")
    fun record(model: Model): Flow<SpeechEvent> = flow {
        // Claimed here, at the top of the flow body, so a stop() issued while the caller was still
        // loading the model has already claimed this number and the loop below never starts.
        val session = generation.incrementAndGet()
        // getMinBufferSize returns ERROR (-1) / ERROR_BAD_VALUE (-2) when the device cannot serve
        // this format. Feeding that to AudioRecord throws IllegalArgumentException out of the flow
        // and takes the collector down; report "no speech" instead, which the UI already handles.
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (bufferSize <= 0) {
            logError(LogTag.SPEECH, "AudioRecord rejects ${sampleRate}Hz mono 16-bit (code $bufferSize)")
            emit(SpeechEvent.NoSpeech)
            return@flow
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize * 2,
        )
        // An AudioRecord that failed to initialise (mic held by another app, resource exhaustion)
        // reads nothing forever. Release it here rather than spin on a dead session.
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            logError(LogTag.SPEECH, "AudioRecord did not initialise (state=${recorder.state})")
            recorder.release()
            emit(SpeechEvent.NoSpeech)
            return@flow
        }
        // Effects and the recognizer are acquired inside a guard, not before the main `try`: only the
        // last acquisition would be covered by that `finally`, so a throw from `Recognizer(...)` — a
        // truncated model directory, native OOM — left the AudioRecord and its effects unreleased.
        // A leaked AudioRecord holds a microphone input session for the life of the process, which
        // can lock the mic out of this app and sometimes others.
        val effects: Effects
        val recognizer: Recognizer
        try {
            effects = Effects.attach(recorder.audioSessionId)
        } catch (e: Throwable) {
            recorder.release()
            throw e
        }
        try {
            recognizer = Recognizer(model, sampleRate.toFloat())
        } catch (e: Throwable) {
            recorder.release()
            effects.release()
            throw e
        }

        var lastCompleted = ""
        try {
            recorder.startRecording()
            val buffer = ShortArray(BUFFER_SAMPLES)
            while (stopRequested.get() < session && currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                // Negative is an error code (ERROR_DEAD_OBJECT when the session is torn down under
                // us, ERROR_INVALID_OPERATION after a failed start). `continue` on those spins a
                // hot loop that never recovers, so end the session and let the flush report what
                // was heard up to that point. Zero is a normal empty read.
                if (read < 0) {
                    logWarn(LogTag.SPEECH, "AudioRecord.read failed (code $read); ending the session and flushing")
                    break
                }
                if (read == 0) continue
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
            // Mark this session stopped however it ended — cancellation, a read error, a throw —
            // so a stop() that arrives afterwards is not left pointing at a session that is gone.
            stopRequested.set(session)
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
