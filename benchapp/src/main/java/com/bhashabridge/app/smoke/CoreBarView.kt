package com.bhashabridge.app.smoke

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Purpose:  Draws per-core share of the process's work as a bar per core, big cores highlighted.
 * Owns:     Two `Paint`s and the values, all allocated once.
 * Lifetime: View.
 * Thread:   Main.
 *
 * This replaces a line of text that read
 * `cpu0E 7% cpu1E 8% cpu2E 19% cpu3E 9% cpu4P 24% ...` and wrapped across two lines mid-value. The
 * question it answers — "did the work stay on the big cluster?" — is a shape, and eight numbers in a
 * row is the one format that hides a shape. Performance cores are drawn in the accent colour and
 * efficiency cores dimmed, so a run that leaked onto the little cluster is visible without reading
 * any digits at all.
 *
 * Bars are scaled to the largest share rather than to 1.0: shares sum to one across eight cores, so
 * against a 0..1 axis every bar would be a stub.
 */
class CoreBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = spToPx(9f)
    }

    private var shares: List<Double> = emptyList()
    private var performanceCoreIds: List<Int> = emptyList()
    private val rect = RectF()

    fun setData(shares: List<Double>, performanceCoreIds: List<Int>) {
        this.shares = shares
        this.performanceCoreIds = performanceCoreIds
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, dpToPx(HEIGHT_DP).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (shares.isEmpty()) return

        val labelHeight = dpToPx(14f)
        val valueHeight = dpToPx(12f)
        val plotHeight = height - labelHeight - valueHeight
        val slot = width.toFloat() / shares.size
        val barWidth = slot * 0.6f
        val radius = dpToPx(2f)
        val maxShare = shares.max().coerceAtLeast(0.0001)

        shares.forEachIndexed { i, share ->
            val isPerf = i in performanceCoreIds
            val centre = slot * i + slot / 2f
            // A floor of 2dp so a core that took no work is still a visible empty slot rather than
            // nothing at all — "this core did nothing" is a result worth seeing.
            val barHeight = (plotHeight * (share / maxShare).toFloat()).coerceAtLeast(dpToPx(2f))

            barPaint.color = if (isPerf) ACCENT else DIM
            rect.set(
                centre - barWidth / 2f,
                valueHeight + (plotHeight - barHeight),
                centre + barWidth / 2f,
                valueHeight + plotHeight,
            )
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            textPaint.color = if (isPerf) ACCENT else MUTED
            canvas.drawText("${Math.round(share * 100)}%", centre, valueHeight - dpToPx(2f), textPaint)

            textPaint.color = MUTED
            canvas.drawText(
                if (isPerf) "$i·P" else "$i·E",
                centre,
                height - dpToPx(2f),
                textPaint,
            )
        }
    }

    private fun dpToPx(dp: Float) = dp * resources.displayMetrics.density
    private fun spToPx(sp: Float) = sp * resources.displayMetrics.scaledDensity

    private companion object {
        const val HEIGHT_DP = 96f
        val ACCENT = Color.parseColor("#6EE7B7")
        val DIM = Color.parseColor("#3A4553")
        val MUTED = Color.parseColor("#8B949E")
    }
}
