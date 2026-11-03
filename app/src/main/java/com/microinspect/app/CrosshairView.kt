package com.microinspect.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * Center crosshair overlay. Normally hidden (alpha 0) — it's flashed
 * briefly via [flashOnPeakFocus] whenever the sharpness meter hits a new
 * peak, as a "focus confirmed" cue, then fades back out rather than
 * staying on screen all the time.
 */
class CrosshairView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    init {
        alpha = 0f
    }

    private var fadeOutRunnable: Runnable? = null

    /**
     * Shows the crosshair at full opacity, holds briefly, then fades it
     * back out. Calling this again while a flash is already in progress
     * restarts the hold/fade timing rather than stacking animations.
     */
    fun flashOnPeakFocus() {
        fadeOutRunnable?.let { removeCallbacks(it) }
        animate().cancel()
        alpha = 1f

        val runnable = Runnable {
            animate().alpha(0f).setDuration(400).start()
        }
        fadeOutRunnable = runnable
        postDelayed(runnable, 500)
    }

    private val linePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val outlinePaint = Paint().apply {
        color = Color.parseColor("#80000000")
        strokeWidth = 6f
        isAntiAlias = true
    }

    private val circlePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val armLength = minOf(width, height) * 0.06f
        val gap = armLength * 0.35f
        val circleRadius = armLength * 1.8f

        // Draw dark outline first for contrast against any background, then the green line on top.
        for (paint in listOf(outlinePaint, linePaint)) {
            // Horizontal arms
            canvas.drawLine(cx - armLength - gap, cy, cx - gap, cy, paint)
            canvas.drawLine(cx + gap, cy, cx + armLength + gap, cy, paint)
            // Vertical arms
            canvas.drawLine(cx, cy - armLength - gap, cx, cy - gap, paint)
            canvas.drawLine(cx, cy + gap, cx, cy + armLength + gap, paint)
        }

        canvas.drawCircle(cx, cy, circleRadius, circlePaint)
    }
}
