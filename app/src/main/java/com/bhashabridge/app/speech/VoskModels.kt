package com.bhashabridge.app.speech

import android.content.Context
import com.bhashabridge.app.Direction
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.logDebug
import org.vosk.Model

/**
 * Purpose:  Loads and holds the Vosk acoustic model for each [Direction]'s *source* language.
 * Owns:     One native [Model] per direction, loaded on first use.
 * Lifetime: Process — constructed and [release]d by [com.bhashabridge.app.BhashaBridgeApp].
 * Thread:   [model] blocks (asset unpack + native load); call it off the main thread. Synchronised
 *           so the audio thread and the UI-triggered warm-up cannot race.
 *
 * The model is keyed by [Direction], not by a language string, so "which recogniser" can never
 * drift out of step with "which translation direction" — LESSONS_FROM_V3 L11, where v3.4.1's
 * audio-file import kept transcribing with the English model in HI→EN mode because nothing tied
 * the two together.
 *
 * A `Recognizer` is deliberately *not* held here. It is bound to one model for its whole life, so
 * v3.4.1 had to rebuild it on every swap and got it wrong when a swap raced a load. Here each
 * recording session builds its own from the model it is about to use — cheap, and impossible to
 * point at the wrong language.
 */
class VoskModels(private val context: Context) {

    private val models = HashMap<Direction, Model>()

    /** The model that recognises [direction]'s source language. Blocks on first call. */
    @Synchronized
    fun model(direction: Direction): Model = models.getOrPut(direction) {
        val folder = assetFolder(direction)
        logDebug(LogTag.SPEECH) { "Loading Vosk model: $folder" }
        Model(AssetFolder.unpack(context, folder))
    }

    /** True if [direction]'s model is already resident — lets the UI avoid a blocking wait. */
    @Synchronized
    fun isLoaded(direction: Direction): Boolean = models.containsKey(direction)

    @Synchronized
    fun release() {
        models.values.forEach { it.close() }
        models.clear()
    }

    private fun assetFolder(direction: Direction) = when (direction) {
        Direction.EN_TO_HI -> "model"     // English acoustic model
        Direction.HI_TO_EN -> "model-hi"  // Hindi acoustic model
    }
}
