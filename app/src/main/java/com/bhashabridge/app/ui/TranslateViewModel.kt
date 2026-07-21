package com.bhashabridge.app.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bhashabridge.app.BhashaBridgeApp
import com.bhashabridge.app.Direction
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.R
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logError
import com.bhashabridge.app.speech.AsrCorrector
import com.bhashabridge.app.speech.AudioCapture
import com.bhashabridge.app.speech.SpeechEvent
import com.bhashabridge.app.speech.Tts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Purpose:  Orchestrates the translation screen — direction, engine warm-up, every translation,
 *           and the speech session. The single place that decides *what* to translate and *when*.
 * Owns:     The MT dispatcher, the [AudioCapture] session, and the [Tts] engine. Not the models:
 *           MT engines and Vosk models are borrowed from [BhashaBridgeApp] at process scope and
 *           never released here (R4.3/R4.4).
 * Lifetime: ViewModel — survives configuration change, so a rotation mid-translation neither
 *           restarts the job nor reloads a model.
 * Thread:   Public API is main-thread. MT calls run on [mt]; capture runs on IO inside its flow.
 *
 * This is the class that stops [MainActivity] from becoming v3.4.1's 961-line Activity. The
 * Activity renders [state] and forwards taps; it has no reference to an engine, a recogniser or a
 * decoder, and cannot acquire one.
 */
class TranslateViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<BhashaBridgeApp>()

    /**
     * One thread for all MT work. `MtEngine` is explicitly *not* internally synchronised — one
     * translation at a time — and cancelling a coroutine cannot interrupt a blocking `translate()`
     * already inside ONNX Runtime. A single-thread dispatcher makes overlap impossible rather than
     * unlikely; it is the structural version of v3.4.1's `translateExecutor`.
     */
    private val mt: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "bb-mt") }.asCoroutineDispatcher()

    private val capture = AudioCapture()
    private val tts = Tts(application) { onTtsReady() }

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    /**
     * Two high-rate, fire-and-forget streams kept *out* of [state]: a waveform level arrives per
     * audio buffer, and a transcript must be written into an EditText the user also owns. Folding
     * either into the state object would re-render the whole screen tens of times a second, or
     * fight the user's cursor on every unrelated state change.
     */
    private val _amplitude = MutableSharedFlow<Float>(extraBufferCapacity = 8)
    val amplitude: SharedFlow<Float> = _amplitude.asSharedFlow()

    private val _transcript = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val transcript: SharedFlow<String> = _transcript.asSharedFlow()

    private var translation: Job? = null
    private var micJob: Job? = null
    private val history = ArrayDeque<HistoryEntry>()

    // Streaming gate: partial results repeat and arrive far faster than the translator can serve.
    private var lastStreamed = ""
    private var lastStreamAt = 0L

    init {
        viewModelScope.launch { load(Direction.EN_TO_HI, initial = true) }
    }

    // ── Translation ──────────────────────────────────────────────────────────────────────────

    /**
     * Translate [text] in the current direction. Blank input is a no-op, not an error state. The
     * "heard" hint is cleared here because typed text has no recogniser output behind it.
     */
    fun translate(text: String) {
        _state.update { it.copy(heard = null) }
        translate(text, final = true)
    }

    private fun translate(text: String, final: Boolean) {
        val input = text.trim()
        if (input.isEmpty()) return
        val direction = _state.value.direction
        // Newest request wins. A cancelled job cannot publish its result, so a partial that is
        // still inside ONNX Runtime when the final arrives finishes silently and is discarded.
        translation?.cancel()
        translation = viewModelScope.launch {
            if (final) {
                _state.update { it.copy(output = Output.InProgress, micStatus = R.string.mic_translating) }
            }
            val result = withContext(mt) {
                try {
                    app.translator(direction).translate(input)
                } catch (e: Throwable) {
                    logError(LogTag.UI, "Translate failed: $direction", e)
                    null
                }
            }
            when {
                result == null -> _state.update {
                    it.copy(output = Output.Failed(R.string.output_failed), micStatus = R.string.mic_tap_to_speak)
                }
                final -> {
                    remember(input, result)
                    _state.update { it.copy(output = Output.Final(result), micStatus = R.string.mic_ready) }
                    tts.speak(result, direction)
                }
                else -> _state.update { it.copy(output = Output.Streaming(result)) }
            }
        }
    }

    /**
     * Flip EN→HI / HI→EN. The direction only changes once the target engine is actually loaded —
     * the screen never sits in a direction it cannot translate. v3.4.1 had this same rule, spelled
     * out across a `pendingDirection` field and two callbacks.
     */
    fun swapDirection() {
        val target = _state.value.direction.opposite()
        stopRecording()
        translation?.cancel()
        viewModelScope.launch { load(target, initial = false) }
    }

    private suspend fun load(direction: Direction, initial: Boolean) {
        _state.update {
            it.copy(loadingMessage = R.string.loading_translation_model, canTranslate = false)
        }
        val loaded = withContext(mt) {
            try {
                app.translator(direction)
                true
            } catch (e: Throwable) {
                // Expected for HI→EN in this build: those cached graphs have not been exported yet.
                logError(LogTag.UI, "Engine unavailable: $direction", e)
                false
            }
        }
        _state.update {
            when {
                loaded -> it.copy(
                    direction = direction,
                    loadingMessage = null,
                    canTranslate = true,
                    output = Output.Empty,
                    micStatus = R.string.mic_tap_to_speak,
                    heard = null,
                )
                // Initial load failing leaves nothing usable; a failed swap keeps the working
                // direction and just reports why the other one did not open.
                initial -> it.copy(
                    loadingMessage = null,
                    canTranslate = false,
                    output = Output.Failed(R.string.error_direction_unavailable),
                )
                else -> it.copy(
                    loadingMessage = null,
                    canTranslate = true,
                    output = Output.Failed(R.string.error_direction_unavailable),
                )
            }
        }
        logDebug(LogTag.UI) { "Direction $direction loaded=$loaded" }
    }

    // ── Speech ───────────────────────────────────────────────────────────────────────────────

    /**
     * Starts a recording session. The caller must already hold RECORD_AUDIO — `AudioRecord` returns
     * silence rather than failing without it, so a missing permission would look like a broken mic.
     */
    fun startRecording() {
        if (micJob != null) return
        val direction = _state.value.direction
        lastStreamed = ""
        lastStreamAt = 0L
        micJob = viewModelScope.launch {
            _state.update { it.copy(mic = MicState.Loading, micStatus = R.string.mic_loading) }
            // First mic use of the session pays the model load; later ones find it resident.
            val model = withContext(Dispatchers.IO) {
                try {
                    app.speechModels.model(direction)
                } catch (e: Throwable) {
                    logError(LogTag.UI, "Speech model unavailable: $direction", e)
                    null
                }
            }
            if (model == null) {
                _state.update { it.copy(mic = MicState.Idle, micStatus = R.string.mic_unavailable) }
                return@launch
            }
            _state.update { it.copy(mic = MicState.Listening, micStatus = R.string.mic_listening) }
            try {
                capture.record(model).collect { event -> onSpeechEvent(event, direction) }
            } finally {
                _state.update { it.copy(mic = MicState.Idle) }
            }
        }
        micJob?.invokeOnCompletion { micJob = null }
    }

    /** Ends the session cleanly: the flow still emits its final transcript before completing. */
    fun stopRecording() {
        capture.stop()
    }

    /** The user denied the microphone. Not an error — just say why nothing happened. */
    fun onMicPermissionDenied() {
        _state.update { it.copy(mic = MicState.Idle, micStatus = R.string.mic_permission_required) }
    }

    private fun onSpeechEvent(event: SpeechEvent, direction: Direction) = when (event) {
        is SpeechEvent.Amplitude -> {
            _amplitude.tryEmit(event.level)
            Unit
        }
        is SpeechEvent.Partial -> maybeStreamTranslate(event.text, direction)
        is SpeechEvent.Final -> {
            val corrected = AsrCorrector.correct(event.text, direction)
            // Surface the raw text when correction changed it, so the user can catch a
            // mis-hearing the tables did not fully repair.
            val heard = event.text.takeIf { !it.equals(corrected, ignoreCase = true) }
            _state.update { it.copy(heard = heard) }
            _transcript.tryEmit(corrected)
            translate(corrected, final = true)
        }
        SpeechEvent.NoSpeech -> {
            _state.update { it.copy(micStatus = R.string.mic_no_speech) }
        }
    }

    /**
     * Live translation of interim speech, gated three ways: at least [STREAM_MIN_WORDS] words (one
     * or two words in isolation translate badly), at most one job per [STREAM_THROTTLE_MS] (the
     * translator is a single sequential resource), and only when the text actually changed (Vosk
     * repeats the same partial across many buffers while the user pauses mid-word).
     */
    private fun maybeStreamTranslate(text: String, direction: Direction) {
        val now = System.currentTimeMillis()
        val words = text.trim().split(WHITESPACE).size
        if (words < STREAM_MIN_WORDS) return
        if (now - lastStreamAt < STREAM_THROTTLE_MS) return
        if (text == lastStreamed) return
        lastStreamed = text
        lastStreamAt = now
        translate(AsrCorrector.correct(text, direction), final = false)
    }

    // ── History ──────────────────────────────────────────────────────────────────────────────

    /**
     * Recent translations, newest first. In memory only and not persisted, matching v3.4.1 — this
     * is a "what did I just say" affordance, not a saved document, and translations of speech are
     * exactly the kind of content that should not outlive the session on disk.
     */
    fun history(): List<HistoryEntry> = history.toList().asReversed()

    /** Puts a past pair back on screen. No engine call — the result is already known. */
    fun recall(entry: HistoryEntry) {
        translation?.cancel()
        _transcript.tryEmit(entry.source)
        _state.update { it.copy(output = Output.Final(entry.target)) }
    }

    private fun remember(source: String, target: String) {
        if (source.isBlank() || target.isBlank()) return
        if (history.size >= HISTORY_SIZE) history.removeFirst()
        history.addLast(HistoryEntry(source, target))
    }

    // ── Emergency phrases ────────────────────────────────────────────────────────────────────

    /**
     * Shows a curated phrase as if it had been translated. No engine call: the pair is
     * human-translated, which is the entire point of the feature.
     */
    fun showEmergencyPhrase(phrase: EmergencyPhrases.Phrase) {
        translation?.cancel()
        _transcript.tryEmit(phrase.english)
        _state.update { it.copy(output = Output.Final(phrase.hindi)) }
    }

    /** Emergency phrases are always spoken in Hindi — the curated pairs only run that way. */
    fun speakEmergencyPhrase(hindi: String) {
        tts.speak(hindi, Direction.EN_TO_HI)
    }

    private fun onTtsReady() {
        _state.update { it.copy(hindiVoiceMissing = !tts.hindiVoiceAvailable) }
    }

    override fun onCleared() {
        super.onCleared()
        // Only what this class owns. The engines and Vosk models belong to the process-scoped
        // owner; releasing them here would re-create v3.4.1's reload-per-rotation.
        capture.stop()
        tts.shutdown()
        mt.close()
    }

    private companion object {
        const val STREAM_MIN_WORDS = 3
        const val STREAM_THROTTLE_MS = 250L
        /** v3.4.1 kept 5. Ten still fits one dialog without scrolling and covers a real exchange. */
        const val HISTORY_SIZE = 10
        val WHITESPACE = "\\s+".toRegex()
    }
}

/** One past translation, source and target as they were shown. */
data class HistoryEntry(val source: String, val target: String)

/** What the output card is showing. */
sealed interface Output {
    data object Empty : Output
    data object InProgress : Output
    /** Live translation of speech still in progress — shown dimmed, never spoken or committed. */
    data class Streaming(val text: String) : Output
    data class Final(val text: String) : Output
    data class Failed(@StringRes val message: Int) : Output
}

/** Where the microphone is in its cycle. */
enum class MicState { Idle, Loading, Listening }

/**
 * Everything the translation screen renders. One immutable snapshot — the Activity draws it and
 * holds no UI state of its own, so a rotation cannot lose or contradict it.
 */
data class TranslateUiState(
    val direction: Direction = Direction.EN_TO_HI,
    /** Non-null means the loading overlay is up, showing this message. */
    @StringRes val loadingMessage: Int? = R.string.loading_initialising,
    val output: Output = Output.Empty,
    val canTranslate: Boolean = false,
    val mic: MicState = MicState.Idle,
    /** Raw recogniser output, set only when ASR correction changed it. */
    val heard: String? = null,
    @StringRes val micStatus: Int = R.string.mic_tap_to_speak,
    val hindiVoiceMissing: Boolean = false,
)
