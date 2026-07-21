package com.bhashabridge.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.os.LocaleListCompat
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bhashabridge.app.BuildConfig
import com.bhashabridge.app.Direction
import com.bhashabridge.app.R
import com.bhashabridge.app.mt.ExecutionPolicy
import kotlinx.coroutines.launch

/**
 * Purpose:  Hosts and renders the translation screen.
 * Owns:     View references only.
 * Lifetime: View
 * Thread:   Main.
 *
 * Renders [TranslateViewModel.state] and forwards taps. It holds no translation state, has no
 * reference to an engine or a recogniser, and does no work off the main thread — every decision
 * about what to translate lives in the ViewModel. v3.4.1's equivalent reached 961 lines by taking
 * that job one reasonable commit at a time.
 */
class MainActivity : AppCompatActivity() {

    private val viewModel: TranslateViewModel by viewModels()

    private lateinit var inputText: EditText
    private lateinit var outputText: TextView
    private lateinit var langSrc: TextView
    private lateinit var langTgt: TextView
    private lateinit var labelInput: TextView
    private lateinit var labelOutput: TextView
    private lateinit var swapBtn: ImageButton
    private lateinit var translateButton: Button
    private lateinit var loadingOverlay: FrameLayout
    private lateinit var loadingStatus: TextView
    private lateinit var loadingDots: List<View>
    private lateinit var micButton: ImageButton
    private lateinit var micPulse: View
    private lateinit var micStatus: TextView
    private lateinit var waveform: WaveformView
    private lateinit var ttsBanner: TextView
    private lateinit var asrHint: TextView
    private lateinit var emergency: EmergencySheet
    private lateinit var drawer: DrawerLayout

    /** Mic permission. Granting resumes the exact action the user tapped for, with no second tap. */
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording() else viewModel.onMicPermissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // First run: the tour and language choice come first. Launched, not routed to, so the
        // engine warm-up below still starts immediately behind it.
        if (WelcomeActivity.isFirstRun(this)) {
            startActivity(Intent(this, WelcomeActivity::class.java))
        }
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        applySystemBarInsets()
        bindViews()

        translateButton.setOnClickListener {
            hideKeyboard()
            viewModel.translate(inputText.text.toString())
        }
        swapBtn.setOnClickListener {
            inputText.setText("")
            viewModel.swapDirection()
        }
        micButton.setOnClickListener { onMicTapped() }
        findViewById<Button>(R.id.emergencyButton).setOnClickListener {
            viewModel.stopRecording()
            emergency.open()
        }
        ttsBanner.setOnClickListener {
            startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.state.collect(::render) }
                launch { viewModel.amplitude.collect(waveform::push) }
                launch { viewModel.transcript.collect(::showTranscript) }
            }
        }
    }

    /** A recording session must not outlive the visible screen — the mic light would stay on. */
    override fun onStop() {
        super.onStop()
        viewModel.stopRecording()
    }

    private fun bindViews() {
        inputText = findViewById(R.id.inputText)
        outputText = findViewById(R.id.outputText)
        langSrc = findViewById(R.id.langSrc)
        langTgt = findViewById(R.id.langTgt)
        labelInput = findViewById(R.id.labelInput)
        labelOutput = findViewById(R.id.labelOutput)
        swapBtn = findViewById(R.id.swapBtn)
        translateButton = findViewById(R.id.translateButton)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        loadingStatus = findViewById(R.id.loadingStatus)
        loadingDots = listOf(findViewById(R.id.dot1), findViewById(R.id.dot2), findViewById(R.id.dot3))
        micButton = findViewById(R.id.micButton)
        micPulse = findViewById(R.id.micPulse)
        micStatus = findViewById(R.id.micStatus)
        waveform = findViewById(R.id.waveformView)
        ttsBanner = findViewById(R.id.ttsBanner)
        asrHint = findViewById(R.id.asrHint)
        emergency = EmergencySheet(
            root = findViewById(R.id.main),
            onChosen = viewModel::showEmergencyPhrase,
            onSpeak = viewModel::speakEmergencyPhrase,
        )
        drawer = findViewById(R.id.drawerLayout)
        animateLoadingDots()
        bindDrawer()

        // Both are overlays, not screens: Back dismisses them before it leaves the app.
        onBackPressedDispatcher.addCallback(this) {
            when {
                emergency.isOpen -> emergency.close()
                drawer.isDrawerOpen(GravityCompat.START) -> drawer.closeDrawers()
                else -> {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        }
    }

    private fun bindDrawer() {
        findViewById<ImageButton>(R.id.menuBtn).setOnClickListener {
            drawer.openDrawer(GravityCompat.START)
        }
        fun item(id: Int, action: () -> Unit) = findViewById<TextView>(id).setOnClickListener {
            drawer.closeDrawers()
            action()
        }
        item(R.id.trayHistory, ::showHistory)
        item(R.id.trayLanguage, ::showLanguagePicker)
        item(R.id.trayAbout, ::showAbout)
    }

    private fun showHistory() {
        val entries = viewModel.history()
        if (entries.isEmpty()) {
            Toast.makeText(this, R.string.history_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries
            .map { "${it.source.ellipsise()}  →  ${it.target.ellipsise()}" }
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.history_title)
            .setItems(labels) { _, index -> viewModel.recall(entries[index]) }
            .setNegativeButton(R.string.btn_dismiss, null)
            .show()
    }

    /**
     * Per-app locales: one call re-creates the Activity with the chosen language and AppCompat
     * persists the choice itself. v3.4.1 stored a "ui_language" preference and then re-applied
     * ~40 inline `if (isHindi)` string pairs by hand on every change.
     */
    private fun showLanguagePicker() {
        AlertDialog.Builder(this)
            .setTitle(R.string.language_title)
            .setMessage(R.string.language_message)
            .setPositiveButton(R.string.language_english) { _, _ -> setUiLanguage("en") }
            .setNegativeButton(R.string.language_hindi) { _, _ -> setUiLanguage("hi") }
            .show()
    }

    private fun setUiLanguage(tag: String) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    /** Reads the live runtime rather than restating it, so this cannot drift from what is running. */
    private fun showAbout() {
        val policy = ExecutionPolicy.current
        val body = buildString {
            appendLine(getString(R.string.about_model))
            appendLine()
            appendLine(getString(R.string.about_speech))
            appendLine()
            append(getString(R.string.about_offline))
            if (BuildConfig.DEBUG) {
                appendLine()
                appendLine()
                appendLine(getString(R.string.about_debug_header))
                appendLine(getString(R.string.about_cpu, ExecutionPolicy.capabilities.describe()))
                append(
                    getString(
                        R.string.about_policy,
                        policy.name,
                        policy.intraThreads,
                        policy.cpuArena.toString(),
                    )
                )
            }
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.about_title)
            .setMessage(body)
            .setPositiveButton(R.string.btn_dismiss, null)
            .show()
    }

    private fun String.ellipsise(limit: Int = 28) =
        if (length > limit) take(limit) + "…" else this

    private fun onMicTapped() {
        if (viewModel.state.value.mic == MicState.Listening) {
            viewModel.stopRecording()
            return
        }
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startRecording() else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun showTranscript(text: String) {
        inputText.setText(text)
        inputText.setSelection(text.length)
    }

    private fun render(state: TranslateUiState) {
        renderDirection(state.direction)
        renderOutput(state.output)
        renderLoading(state.loadingMessage)
        renderMic(state)
        translateButton.isEnabled = state.canTranslate
        swapBtn.isEnabled = state.loadingMessage == null
        ttsBanner.isVisible = state.hindiVoiceMissing
        asrHint.isVisible = state.heard != null
        state.heard?.let { asrHint.text = getString(R.string.asr_heard, it) }
    }

    private fun renderDirection(direction: Direction) {
        val source = if (direction == Direction.EN_TO_HI) R.string.lang_english else R.string.lang_hindi
        val target = if (direction == Direction.EN_TO_HI) R.string.lang_hindi else R.string.lang_english
        langSrc.setText(source)
        langTgt.setText(target)
        labelInput.setText(if (direction == Direction.EN_TO_HI) R.string.label_english else R.string.label_hindi)
        labelOutput.setText(if (direction == Direction.EN_TO_HI) R.string.label_hindi else R.string.label_english)
        inputText.setHint(
            if (direction == Direction.EN_TO_HI) R.string.hint_english else R.string.hint_hindi
        )
        // Sentence capitalisation is an English-script affordance; Devanagari has no letter case.
        inputText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            if (direction == Direction.EN_TO_HI) InputType.TYPE_TEXT_FLAG_CAP_SENTENCES else 0
    }

    private fun renderOutput(output: Output) = when (output) {
        Output.Empty -> setOutput(getString(R.string.output_placeholder), R.color.output_idle)
        Output.InProgress -> setOutput(getString(R.string.output_translating), R.color.output_streaming)
        is Output.Streaming -> setOutput(output.text, R.color.output_streaming)
        is Output.Final -> setOutput(output.text, R.color.output_result)
        is Output.Failed -> setOutput(getString(output.message), R.color.output_idle)
    }

    private fun setOutput(text: String, @ColorRes colorRes: Int) {
        outputText.text = text
        outputText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun renderMic(state: TranslateUiState) {
        micStatus.setText(state.micStatus)
        micButton.isEnabled = state.loadingMessage == null && state.mic != MicState.Loading
        val listening = state.mic == MicState.Listening
        if (listening == micPulse.isVisible) return // already in this state; do not restart the pulse
        if (listening) {
            waveform.visibility = View.VISIBLE
            waveform.reset()
            micPulse.visibility = View.VISIBLE
            micPulse.startAnimation(AlphaAnimation(1.0f, 0.2f).apply {
                duration = 600
                repeatMode = Animation.REVERSE
                repeatCount = Animation.INFINITE
            })
        } else {
            waveform.reset()
            waveform.visibility = View.INVISIBLE
            micPulse.clearAnimation()
            micPulse.visibility = View.INVISIBLE
        }
    }

    private fun renderLoading(@StringRes messageRes: Int?) {
        if (messageRes == null) {
            if (loadingOverlay.isVisible) {
                loadingOverlay.animate().alpha(0f).setDuration(250)
                    .withEndAction { loadingOverlay.visibility = View.GONE }.start()
            }
            return
        }
        loadingStatus.setText(messageRes)
        if (!loadingOverlay.isVisible) {
            loadingOverlay.alpha = 1f
            loadingOverlay.visibility = View.VISIBLE
            animateLoadingDots()
        }
    }

    /** Cycles the three overlay dots. Self-terminating: reschedules only while the overlay is up. */
    private fun animateLoadingDots() {
        var step = 0
        val tick = object : Runnable {
            override fun run() {
                if (!loadingOverlay.isVisible) return
                loadingDots.forEachIndexed { i, dot -> dot.alpha = if (i == step % 3) 1f else 0.3f }
                step++
                loadingOverlay.postDelayed(this, 300)
            }
        }
        loadingOverlay.post(tick)
    }

    private fun hideKeyboard() {
        getSystemService<InputMethodManager>()?.hideSoftInputFromWindow(inputText.windowToken, 0)
    }

    private fun applySystemBarInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }
}
