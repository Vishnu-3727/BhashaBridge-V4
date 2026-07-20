package com.bhashabridge.app.ui

import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.bhashabridge.app.R

/**
 * Purpose:  Hosts the translation screen.
 * Owns:     Nothing.
 * Lifetime: View
 * Thread:   Main.
 *
 * Renders only. Orchestration belongs to TranslateViewModel when it arrives (R3.3) — this Activity
 * must never acquire the job of deciding what to translate or when. V3.4.1's equivalent reached
 * 961 lines by taking that job one reasonable commit at a time.
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }
}
