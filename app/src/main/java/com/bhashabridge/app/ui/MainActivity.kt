package com.bhashabridge.app.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.bhashabridge.app.Direction
import com.bhashabridge.app.R
import kotlinx.coroutines.launch

/**
 * Purpose:  Hosts and renders the translation screen.
 * Owns:     View references only.
 * Lifetime: View
 * Thread:   Main.
 *
 * Renders [TranslateViewModel.state] and forwards taps. It holds no translation state, has no
 * reference to an engine, and does no work off the main thread — every decision about what to
 * translate lives in the ViewModel. v3.4.1's equivalent reached 961 lines by taking that job one
 * reasonable commit at a time.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
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
        animateLoadingDots()
    }

    private fun render(state: TranslateUiState) {
        renderDirection(state.direction)
        renderOutput(state.output)
        renderLoading(state.loadingMessage)
        translateButton.isEnabled = state.canTranslate
        swapBtn.isEnabled = state.loadingMessage == null
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
        is Output.Final -> setOutput(output.text, R.color.output_result)
        is Output.Failed -> setOutput(getString(output.message), R.color.output_idle)
    }

    private fun setOutput(text: String, @ColorRes colorRes: Int) {
        outputText.text = text
        outputText.setTextColor(ContextCompat.getColor(this, colorRes))
    }

    private fun renderLoading(@StringRes messageRes: Int?) {
        if (messageRes == null) {
            if (loadingOverlay.visibility == View.VISIBLE) {
                loadingOverlay.animate().alpha(0f).setDuration(250)
                    .withEndAction { loadingOverlay.visibility = View.GONE }.start()
            }
            return
        }
        loadingStatus.setText(messageRes)
        if (loadingOverlay.visibility != View.VISIBLE) {
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
                if (loadingOverlay.visibility != View.VISIBLE) return
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
