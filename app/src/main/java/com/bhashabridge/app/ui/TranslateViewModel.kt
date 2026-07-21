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
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

/**
 * Purpose:  Orchestrates the translation screen — owns direction, the engine warm-up, and every
 *           translation request. The single place that decides *what* to translate and *when*.
 * Owns:     The MT dispatcher (one thread) and the UI state. Not the engines: those are borrowed
 *           from [BhashaBridgeApp] at process scope and never released here (R4.3/R4.4).
 * Lifetime: ViewModel — survives configuration change, so a rotation mid-translation neither
 *           restarts the job nor reloads a model.
 * Thread:   Public API is main-thread. Every [com.bhashabridge.app.mt.MtEngine] call runs on [mt].
 *
 * This is the class that stops [MainActivity] from becoming v3.4.1's 961-line Activity. The
 * Activity renders [state] and forwards taps; it has no reference to an engine, a tokenizer, or a
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

    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()

    private var translation: Job? = null

    init {
        viewModelScope.launch { load(Direction.EN_TO_HI, initial = true) }
    }

    /** Translate [text] in the current direction. Blank input is a no-op, not an error state. */
    fun translate(text: String) {
        val input = text.trim()
        if (input.isEmpty()) return
        val direction = _state.value.direction
        translation?.cancel()
        translation = viewModelScope.launch {
            _state.update { it.copy(output = Output.InProgress) }
            val result = runEngine(direction) { it.translate(input) }
            _state.update {
                it.copy(output = result?.let(Output::Final) ?: Output.Failed(R.string.output_failed))
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

    /** Runs [block] on the MT thread, returning null if the engine is missing or the call throws. */
    private suspend fun <T> runEngine(
        direction: Direction,
        block: (com.bhashabridge.app.mt.MtEngine) -> T,
    ): T? = withContext(mt) {
        try {
            block(app.translator(direction))
        } catch (e: Throwable) {
            logError(LogTag.UI, "MT call failed: $direction", e)
            null
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Only the dispatcher. The engines belong to the process-scoped owner, which releases them
        // on onTrimMemory — a ViewModel releasing them would re-create v3.4.1's reload-per-rotation.
        mt.close()
    }
}

/** What the output card is showing. */
sealed interface Output {
    data object Empty : Output
    data object InProgress : Output
    data class Final(val text: String) : Output
    data class Failed(@StringRes val message: Int) : Output
}

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
)
