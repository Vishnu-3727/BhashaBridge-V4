package com.bhashabridge.app.smoke

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.bhashabridge.app.Direction
// R lives in the module's namespace, which this file's package is a child of, so it needs importing.
import com.bhashabridge.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Purpose:  The whole UI — start a run, watch it, read the digest, export the JSON.
 * Owns:     View references only.
 * Lifetime: View.
 * Thread:   Main. The run itself is pushed to [Dispatchers.Default].
 *
 * One screen, no navigation, no settings persistence. The measurement is the product; anything that
 * takes taps to reach is a thing an operator will skip on the fifth device of the day.
 *
 * `KEEP_SCREEN_ON` is set for the duration of a run and cleared afterwards. This is not cosmetic:
 * the screen going off mid-run moves the process down the scheduler's priority list and parks it on
 * the little cluster, which silently turns a CPU benchmark into a measurement of Android's idle
 * policy.
 */
class BenchActivity : AppCompatActivity() {

    private lateinit var runButton: Button
    private lateinit var shareButton: Button
    private lateinit var clearButton: Button
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var modelState: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var presetGroup: RadioGroup
    private lateinit var kleidiBox: CheckBox

    private var lastReport: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_bench)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        runButton = findViewById(R.id.runButton)
        shareButton = findViewById(R.id.shareButton)
        clearButton = findViewById(R.id.clearButton)
        status = findViewById(R.id.status)
        results = findViewById(R.id.results)
        modelState = findViewById(R.id.modelState)
        progress = findViewById(R.id.progress)
        scroll = findViewById(R.id.scroll)
        presetGroup = findViewById(R.id.presetGroup)
        kleidiBox = findViewById(R.id.kleidiBox)

        runButton.setOnClickListener { startRun() }
        shareButton.setOnClickListener { shareReport() }
        clearButton.setOnClickListener { clearModels() }
        // The cost of the choice, before it is paid: Torture is a fourteen-minute commitment and an
        // operator who learns that at minute nine puts the phone down mid-soak.
        presetGroup.setOnCheckedChangeListener { _, _ -> showPresetHint() }

        refreshModelState()
    }

    override fun onResume() {
        super.onResume()
        refreshModelState()
    }

    /**
     * Tells the operator what is missing and the command that fixes it.
     *
     * The missing filenames are no longer listed. They are always the same five, the push command
     * copies them all anyway, and printing them turned a one-line banner into an eight-line block
     * that pushed the results off screen before a run had even started.
     */
    private fun refreshModelState() {
        val missing = ModelStore.missing(this, Direction.EN_TO_HI)
        val speechReady = ModelStore.isSpeechReady(this, Direction.EN_TO_HI)
        val mtHint = ModelStore.pushHint(this)
        val speechHint = ModelStore.speechPushHint(this, Direction.EN_TO_HI)
        // The two phases are independent, so the banner reports them independently. A single
        // "models missing" line would let an operator push the ONNX graphs, see a green tick, and
        // never learn that the speech phase silently did not run.
        modelState.text = when {
            missing.isEmpty() && speechReady -> getString(R.string.models_ready)
            missing.isEmpty() -> getString(R.string.models_ready_mt_only, speechHint)
            speechReady -> getString(R.string.models_ready_speech_only, mtHint)
            else -> getString(R.string.models_missing, missing.size, mtHint, speechHint)
        }
        val anythingReady = missing.isEmpty() || speechReady
        modelState.setTextColor(
            ContextCompat.getColor(this, if (anythingReady) R.color.accent else R.color.text_secondary)
        )
        clearButton.isEnabled = missing.size < ModelStore.required(Direction.EN_TO_HI).size || speechReady
    }

    /** Radio id → preset. Standard is the fallback, matching the layout's checked button. */
    private fun selectedPreset(): BenchRunner.Preset = when (presetGroup.checkedRadioButtonId) {
        R.id.presetLight -> BenchRunner.Preset.LIGHT
        R.id.presetHeavy -> BenchRunner.Preset.HEAVY
        R.id.presetTorture -> BenchRunner.Preset.TORTURE
        else -> BenchRunner.Preset.STANDARD
    }

    private fun showPresetHint() {
        val p = selectedPreset()
        status.text = getString(
            R.string.preset_hint, p.label, p.approxMinutes, p.mtIterations, p.sustainedSeconds,
        )
    }

    /** RadioGroup's own `isEnabled` does not reach its children, so each button is set directly. */
    private fun setPresetEnabled(enabled: Boolean) {
        for (i in 0 until presetGroup.childCount) presetGroup.getChildAt(i).isEnabled = enabled
        kleidiBox.isEnabled = enabled
    }

    private fun startRun() {
        runButton.isEnabled = false
        shareButton.isEnabled = false
        clearButton.isEnabled = false
        // Locked for the duration: the preset is read once at the start, so a mid-run tap would
        // change the label without changing the measurement.
        setPresetEnabled(false)
        progress.visibility = View.VISIBLE
        results.removeAllViews()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val config = BenchRunner.Config(
            preset = selectedPreset(),
            includeKleidiAb = kleidiBox.isChecked,
        )

        lifecycleScope.launch {
            val report = try {
                withContext(Dispatchers.Default) {
                    BenchRunner(this@BenchActivity).run(config) { message ->
                        // The runner is on a worker; every UI touch hops back to main.
                        lifecycleScope.launch { status.text = message }
                    }
                }
            } catch (e: Throwable) {
                status.text = getString(R.string.run_failed, e.message ?: e::class.java.simpleName)
                null
            } finally {
                window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                progress.visibility = View.INVISIBLE
                runButton.isEnabled = true
                setPresetEnabled(true)
            }
            report?.let { render(it) }
            refreshModelState()
        }
    }

    /**
     * Builds the result view: headline, cards, core chart, filename.
     *
     * Inflating views rather than formatting one padded string. The string version had to guess a
     * label column width (15 characters), and every value longer than the remainder wrapped onto the
     * next line mid-number — "(2.7x scaling)" and "arena=false" ended up orphaned on their own rows.
     * A two-view row cannot do that: the value gets the leftover width and ellipsises instead.
     */
    private fun render(report: BenchRunner.Report) {
        status.text = if (report.mtRan) getString(R.string.run_done) else getString(R.string.run_done_cpu_only)
        results.removeAllViews()
        val inflater = LayoutInflater.from(this)

        report.headline?.let { hero ->
            val view = inflater.inflate(R.layout.item_hero, results, false)
            view.findViewById<TextView>(R.id.heroValue).text = hero.value
            view.findViewById<TextView>(R.id.heroUnit).text = hero.unit
            view.findViewById<TextView>(R.id.heroCaption).text = hero.caption
            results.addView(view, spaced())
        }

        report.sections.forEach { section ->
            val card = inflater.inflate(R.layout.item_section, results, false)
            card.findViewById<TextView>(R.id.sectionTitle).text = section.title
            val rows = card.findViewById<LinearLayout>(R.id.sectionRows)
            section.rows.forEach { row ->
                val rowView = inflater.inflate(R.layout.item_row, rows, false)
                rowView.findViewById<TextView>(R.id.rowLabel).text = row.label
                rowView.findViewById<TextView>(R.id.rowValue).apply {
                    text = row.value
                    setTextColor(ContextCompat.getColor(this@BenchActivity, toneColour(row.tone)))
                }
                rows.addView(rowView)
            }
            results.addView(card, spaced())
        }

        report.coreShare?.let { cores ->
            val card = inflater.inflate(R.layout.item_cores, results, false)
            card.findViewById<CoreBarView>(R.id.coreBars).setData(cores.shares, cores.performanceCoreIds)
            results.addView(card, spaced())
        }

        report.file?.let { file ->
            val footer = inflater.inflate(R.layout.item_report_footer, results, false)
            footer.findViewById<TextView>(R.id.reportName).text = getString(R.string.saved_to, file.name)
            results.addView(footer, spaced())
        }

        lastReport = report.file
        shareButton.isEnabled = report.file != null
        // Back to the headline, not the bottom: the number worth reading is at the top.
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun toneColour(tone: BenchRunner.Tone): Int = when (tone) {
        BenchRunner.Tone.GOOD -> R.color.accent
        BenchRunner.Tone.WARN -> R.color.warn
        BenchRunner.Tone.MUTED -> R.color.text_muted
        BenchRunner.Tone.NORMAL -> R.color.text_primary
    }

    /** Uniform gap between cards, so spacing lives in one place rather than in every layout. */
    private fun spaced() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = (10 * resources.displayMetrics.density).toInt() }

    /** FileProvider, so the JSON leaves the device without this app asking for storage access. */
    private fun shareReport() {
        val file = lastReport ?: return
        val uri = FileProvider.getUriForFile(this, "$packageName.reports", file)
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, file.name)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                getString(R.string.share_report),
            )
        )
    }

    private fun clearModels() {
        lifecycleScope.launch {
            val freed = withContext(Dispatchers.IO) { ModelStore.clearStaged(this@BenchActivity) }
            Toast.makeText(this@BenchActivity, getString(R.string.cleared, freed / (1024 * 1024)), Toast.LENGTH_SHORT).show()
            refreshModelState()
        }
    }
}
