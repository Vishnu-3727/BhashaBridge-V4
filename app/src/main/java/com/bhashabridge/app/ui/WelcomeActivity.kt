package com.bhashabridge.app.ui

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ViewFlipper
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bhashabridge.app.R

/**
 * Purpose:  First run only — the feature tour, then the interface-language choice.
 * Owns:     Its own views.
 * Lifetime: One launch, then [finish]. Never returns to the back stack.
 * Thread:   Main.
 *
 * Two pages of one [ViewFlipper]. v3.4.1 used two Activities, one of which existed purely to start
 * the other for a result and forward that result verbatim.
 *
 * It does *not* run v3.4.1's ACTION_CHECK_TTS_DATA prompt at setup. That check asked whether *any*
 * voice data was installed while the dialog talked about Hindi specifically, so it could both nag
 * users who were fine and pass users who were not. The main screen's "Hindi voice not installed"
 * banner is driven by the actual Hindi query and appears exactly when it is true.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_welcome)

        val flipper = findViewById<ViewFlipper>(R.id.welcomeFlipper)
        inflateSteps(findViewById(R.id.tourSteps))

        findViewById<Button>(R.id.btnGetStarted).setOnClickListener { flipper.showNext() }
        findViewById<Button>(R.id.btnEnglish).setOnClickListener { finishWith("en") }
        findViewById<Button>(R.id.btnHindi).setOnClickListener { finishWith("hi") }
    }

    private fun inflateSteps(container: LinearLayout) {
        val inflater = LayoutInflater.from(this)
        STEPS.forEach { step ->
            val row = inflater.inflate(R.layout.item_welcome_step, container, false)
            row.findViewById<TextView>(R.id.stepGlyph).text = step.glyph
            row.findViewById<TextView>(R.id.stepTitle).setText(step.title)
            row.findViewById<TextView>(R.id.stepTitleHindi).setText(step.titleHindi)
            row.findViewById<TextView>(R.id.stepBody).setText(step.body)
            container.addView(row)
        }
    }

    private fun finishWith(languageTag: String) {
        markSeen(this)
        // Per-app locale: the whole UI follows from this one call, including MainActivity below.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(languageTag))
        setResult(Activity.RESULT_OK)
        finish()
    }

    private class Step(
        val glyph: String,
        @StringRes val title: Int,
        @StringRes val titleHindi: Int,
        @StringRes val body: Int,
    )

    companion object {
        private const val PREFS = "bb_prefs"
        private const val KEY_SEEN = "seen_welcome"

        private val STEPS = listOf(
            Step("🎤", R.string.welcome_speak, R.string.welcome_speak_hi, R.string.welcome_speak_body),
            Step("⚡", R.string.welcome_offline, R.string.welcome_offline_hi, R.string.welcome_offline_body),
            Step("🆘", R.string.welcome_emergency, R.string.welcome_emergency_hi, R.string.welcome_emergency_body),
        )

        /** True until the user finishes the flow once; then never again for this install. */
        fun isFirstRun(context: Context): Boolean =
            !context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_SEEN, false)

        private fun markSeen(context: Context) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_SEEN, true).apply()
        }
    }
}
