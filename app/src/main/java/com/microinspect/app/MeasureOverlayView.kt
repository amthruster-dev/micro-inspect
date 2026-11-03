package com.microinspect.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Measurement overlay drawn on top of the camera preview.
 *
 * Two modes:
 *  - Calibration mode: the user taps two points on a reference object of
 *    known real-world length; a line is drawn between them while picking
 *    points, and the resulting pixel distance is reported via
 *    [onCalibrationPointsChosen] once both points are placed.
 *  - Measurement mode (default once calibrated): draws a horizontal ruled
 *    scale across the middle of the view with tick marks every 1 mm
 *    (minor) and labeled marks every 5 mm, using the current pixels-per-mm
 *    value. This approximates the "detachable scale plate" overlay used on
 *    handheld USB microscopes.
 */
class MeasureOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var pixelsPerMm: Float = 0f
        set(value) {
            field = value
            invalidate()
        }

    var calibrationMode: Boolean = false
        set(value) {
            field = value
            if (!value) {
                pointA = null
                pointB = null
            }
            invalidate()
        }

    var onCalibrationPointsChosen: ((pixelDistance: Float) -> Unit)? = null

    private var pointA: PointF? = null
    private var pointB: PointF? = null

    private val scalePaint = Paint().apply {
        color = Color.parseColor("#00E676")
        strokeWidth = 3f
        isAntiAlias = true
    }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#00E676")
        textSize = 28f
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val calLinePaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        strokeWidth = 4f
        isAntiAlias = true
    }

    private val calPointPaint = Paint().apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!calibrationMode) return false
        if (event.action == MotionEvent.ACTION_DOWN) {
            when {
                pointA == null -> {
                    pointA = PointF(event.x, event.y)
                    invalidate()
                }
                pointB == null -> {
                    pointB = PointF(event.x, event.y)
                    invalidate()
                    val a = pointA!!
                    val b = pointB!!
                    val dist = hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()).toFloat()
                    onCalibrationPointsChosen?.invoke(dist)
                }
                else -> {
                    // Start over on a third tap.
                    pointA = PointF(event.x, event.y)
                    pointB = null
                    invalidate()
                }
            }
            return true
        }
        return false
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (calibrationMode) {
            drawCalibrationPoints(canvas)
            return
        }

        if (pixelsPerMm > 0f) {
            drawRuledScale(canvas)
        }
    }

    private fun drawCalibrationPoints(canvas: Canvas) {
        val a = pointA
        val b = pointB
        if (a != null) {
            canvas.drawCircle(a.x, a.y, 14f, calPointPaint)
        }
        if (b != null) {
            canvas.drawCircle(b.x, b.y, 14f, calPointPaint)
        }
        if (a != null && b != null) {
            canvas.drawLine(a.x, a.y, b.x, b.y, calLinePaint)
        }
    }

    /**
     * Draws a horizontal ruler centered in the view, with minor ticks every
     * 1 mm and labeled major ticks every 5 mm, extending as far as the
     * current pixels-per-mm calibration allows on screen.
     */
    private fun drawRuledScale(canvas: Canvas) {
        val centerY = height / 2f
        val centerX = width / 2f

        val mmHalfSpan = (width / 2f / pixelsPerMm)
        val maxMm = mmHalfSpan.toInt()

        // Baseline
        canvas.drawLine(0f, centerY, width.toFloat(), centerY, scalePaint)

        for (mm in -maxMm..maxMm) {
            val x = centerX + mm * pixelsPerMm
            val isMajor = mm % 5 == 0
            val tickHeight = if (isMajor) 28f else 14f
            canvas.drawLine(x, centerY - tickHeight, x, centerY + tickHeight, scalePaint)

            if (isMajor) {
                canvas.drawText(mm.toString(), x, centerY - tickHeight - 10f, labelPaint)
            }
        }
    }
}
