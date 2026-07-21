package com.bhashabridge.app.speech

import android.content.Context
import android.speech.tts.TextToSpeech
import com.bhashabridge.app.Direction
import com.bhashabridge.app.LogTag
import com.bhashabridge.app.logDebug
import com.bhashabridge.app.logError
import com.bhashabridge.app.logWarn
import java.util.Locale

/**
 * Purpose:  Speaks a finished translation in the target language.
 * Owns:     One `TextToSpeech` engine.
 * Lifetime: Held by [com.bhashabridge.app.ui.TranslateViewModel]; [shutdown] on onCleared.
 * Thread:   [speak] from the main thread. [onReady] fires on the engine's own callback thread.
 *
 * Initialisation is asynchronous and may report that the Hindi voice is missing — that is a
 * user-fixable device state ("install voice data"), not an app failure, so it is surfaced via
 * [hindiVoiceAvailable] rather than swallowed.
 */
class Tts(
    context: Context,
    private val onReady: () -> Unit,
) {

    private val engine = TextToSpeech(context.applicationContext) { status -> handleInit(status) }

    @Volatile var ready = false
        private set

    @Volatile var hindiVoiceAvailable = false
        private set

    private fun handleInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            // No fallback engine is constructed here. v3.4.1 replaced the field with an explicit
            // Google-TTS instance, which on a device without that package simply failed a second
            // time; the user-visible outcome ("no speech") is the same, minus a second engine.
            logError(LogTag.SPEECH, "TTS engine failed to initialise (status=$status)")
            return
        }
        val hindi = engine.isLanguageAvailable(HINDI)
        hindiVoiceAvailable = hindi != TextToSpeech.LANG_MISSING_DATA &&
            hindi != TextToSpeech.LANG_NOT_SUPPORTED
        ready = true
        logDebug(LogTag.SPEECH) { "TTS ready, Hindi voice available: $hindiVoiceAvailable" }
        onReady()
    }

    /**
     * Speaks [text] in [direction]'s target language, falling back to the English voice if that
     * language's data is missing. Deliberate: hearing the wrong voice beats silence with no
     * explanation, and the missing-voice banner already tells the user how to fix it.
     */
    fun speak(text: String, direction: Direction) {
        if (!ready || text.isBlank()) return
        try {
            val locale = if (direction == Direction.EN_TO_HI) HINDI else Locale.US
            val result = engine.setLanguage(locale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                logWarn(LogTag.SPEECH, "Voice for $locale unavailable; speaking with the English voice")
                engine.setLanguage(Locale.ENGLISH)
            }
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
        } catch (e: Exception) {
            // Swallowed on purpose: a TTS failure must never disturb the translation on screen.
            logError(LogTag.SPEECH, "TTS speak failed", e)
        }
    }

    fun stop() {
        if (ready) engine.stop()
    }

    fun shutdown() {
        engine.shutdown()
    }

    private companion object {
        val HINDI: Locale = Locale("hi", "IN")
        const val UTTERANCE_ID = "bb_tts_out"
    }
}
