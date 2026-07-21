package com.bhashabridge.app.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bhashabridge.app.R

/**
 * Purpose:  Drives the emergency phrase overlay — tabs, list, and the selected-phrase panel.
 * Owns:     The overlay's views and its selected category.
 * Lifetime: View — constructed with [MainActivity]'s content view.
 * Thread:   Main.
 *
 * Pure presentation over [EmergencyPhrases]. It reports what the user picked through [onChosen]
 * and [onSpeak] and never touches the translation screen or the engine itself, so opening it can
 * neither disturb a translation in flight nor depend on one.
 */
class EmergencySheet(
    private val root: View,
    private val onChosen: (EmergencyPhrases.Phrase) -> Unit,
    private val onSpeak: (String) -> Unit,
) {

    private val overlay: View = root.findViewById(R.id.emergencyOverlay)
    private val tabs: LinearLayout = root.findViewById(R.id.categoryTabs)
    private val list: RecyclerView = root.findViewById(R.id.phraseList)
    private val selectedPanel: View = root.findViewById(R.id.selectedPhrasePanel)
    private val selectedEnglish: TextView = root.findViewById(R.id.selectedEnglish)
    private val selectedHindi: TextView = root.findViewById(R.id.selectedHindi)

    private val adapter = PhraseAdapter(::select)
    private val tabViews: Map<EmergencyPhrases.Category, TextView> =
        EmergencyPhrases.Category.entries.associateWith { category -> addTab(category) }

    val isOpen: Boolean get() = overlay.isVisible

    init {
        list.layoutManager = LinearLayoutManager(root.context)
        list.adapter = adapter
        root.findViewById<Button>(R.id.emergencyClose).setOnClickListener { close() }
        root.findViewById<Button>(R.id.speakPhraseBtn).setOnClickListener {
            onSpeak(selectedHindi.text.toString())
        }
    }

    /** Always reopens on the BASIC tab with no selection — the sheet has no memory between uses. */
    fun open() {
        overlay.isVisible = true
        selectedPanel.isVisible = false
        show(EmergencyPhrases.Category.BASIC)
    }

    fun close() {
        overlay.isVisible = false
    }

    private fun addTab(category: EmergencyPhrases.Category): TextView {
        val tab = TextView(root.context).apply {
            text = category.label
            textSize = 11f
            gravity = android.view.Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            setOnClickListener { show(category) }
        }
        tabs.addView(tab)
        return tab
    }

    private fun show(category: EmergencyPhrases.Category) {
        tabViews.forEach { (tabCategory, tab) ->
            val selected = tabCategory == category
            tab.setTextColor(
                ContextCompat.getColor(
                    root.context,
                    if (selected) R.color.emergency_light else R.color.text_secondary,
                )
            )
            tab.setBackgroundResource(if (selected) R.drawable.bg_tab_selected else 0)
        }
        adapter.submit(EmergencyPhrases.byCategory(category))
    }

    private fun select(phrase: EmergencyPhrases.Phrase) {
        selectedEnglish.text = phrase.english
        selectedHindi.text = phrase.hindi
        selectedPanel.isVisible = true
        onChosen(phrase)
        onSpeak(phrase.hindi)
    }

    private class PhraseAdapter(
        private val onClick: (EmergencyPhrases.Phrase) -> Unit,
    ) : RecyclerView.Adapter<PhraseAdapter.Holder>() {

        private var phrases: List<EmergencyPhrases.Phrase> = emptyList()

        fun submit(items: List<EmergencyPhrases.Phrase>) {
            phrases = items
            notifyDataSetChanged() // whole-list swap on a tab change; nothing partial to diff
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context)
                .inflate(R.layout.item_emergency_phrase, parent, false)
        )

        override fun getItemCount() = phrases.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val phrase = phrases[position]
            holder.english.text = phrase.english
            holder.hindi.text = phrase.hindi
            holder.itemView.setOnClickListener { onClick(phrase) }
        }

        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val english: TextView = view.findViewById(R.id.phraseEnglish)
            val hindi: TextView = view.findViewById(R.id.phraseHindi)
        }
    }
}
