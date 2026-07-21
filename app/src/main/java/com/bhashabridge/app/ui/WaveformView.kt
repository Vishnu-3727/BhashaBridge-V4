package com.bhashabridge.app.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Purpose:  A 32-bar live level meter, driven by amplitude samples pushed from the capture loop.
 * Owns:     Its amplitude buffer and one `Paint`, both allocated once.
 * Lifetime: View — inflated with the layout, garbage-collected with it.
 * Thread:   Main. [push] mutates view state and invalidates.
 *
 * Confirms to the user that audio is actually being captured, and roughly how loud it is, without
 * rendering a real waveform.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6EE7B7")
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
    }

    private val amplitudes = FloatArray(BARS) { IDLE }

    /**
     * A 32-slot shift register: every sample pushes the others one slot along, which is the whole
     * mechanism behind the bars scrolling. The new sample is blended 70/30 with what it replaces
     * purely to damp jitter — without it, two consecutive buffers of different loudness make the
     * leading bar flicker instead of move.
     */
    fun push(level: Float) {
        for (i in BARS - 1 downTo 1) amplitudes[i] = amplitudes[i - 1]
        amplitudes[0] = (level * 0.7f + amplitudes[0] * 0.3f).coerceIn(IDLE, 1f)
        invalidate()
    }

    /** Flattens every bar to the idle floor. Real motion only ever comes from [push]. */
    fun reset() {
        amplitudes.fill(IDLE)
        invalidate()
    }

    /**
     * Bars keep a 25% floor so they stay visible near silence, and brightness is clamped to 80..255
     * because a near-black bar would vanish against the background. Louder samples draw taller and
     * brighter.
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val h = height.toFloat()
        val barWidth = width.toFloat() / (BARS * 2f)

        for (i in 0 until BARS) {
            val x = i * (barWidth * 2) + barWidth / 2
            val barHeight = (0.25f + amplitudes[i] * 0.75f) * h * 0.85f
            val top = (h - barHeight) / 2
            val brightness = (amplitudes[i] * 255).toInt().coerceIn(80, 255)
            paint.color = Color.rgb(brightness / 2, brightness, brightness / 2 + 40)
            canvas.drawLine(x, top, x, top + barHeight, paint)
        }
    }

    private companion object {
        const val BARS = 32
        /** Idle bar height as a fraction of full scale — the flat baseline, and the lower clamp. */
        const val IDLE = 0.05f
    }
}
