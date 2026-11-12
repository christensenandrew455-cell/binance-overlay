package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max

class IndicatorLineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 164, 181)
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 154, 164, 181)
        strokeWidth = density
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.1f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 36, 53) }
    private var chartTitle = "EMA LINE"
    private var values: List<Double> = emptyList()
    private var secondaryValues: List<Double> = emptyList()
    private var lineColor = Color.CYAN
    private var secondaryColor = Color.WHITE

    fun setSeries(title: String, series: List<Double>, color: Int) {
        setSeries(title, series, color, emptyList(), Color.WHITE)
    }

    fun setSeries(title: String, series: List<Double>, color: Int, secondSeries: List<Double>, secondColor: Int) {
        chartTitle = title
        values = series.filter { it.isFinite() }.takeLast(60)
        secondaryValues = secondSeries.filter { it.isFinite() }.takeLast(60)
        lineColor = color
        secondaryColor = secondColor
        linePaint.color = lineColor
        secondaryPaint.color = secondaryColor
        dotPaint.color = lineColor
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 12f * density
        val right = width - 12f * density
        val top = 27f * density
        val bottom = height - 12f * density
        val radius = 11f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, cardPaint)
        canvas.drawText(chartTitle, left, 17f * density, titlePaint)
        val plotHeight = bottom - top
        if (values.size < 2 || right <= left || plotHeight <= 0f) {
            titlePaint.textSize = 11f * density
            canvas.drawText("Waiting for candle history…", left, top + plotHeight / 2f, titlePaint)
            titlePaint.textSize = 10f * density
            return
        }
        for (i in 0..3) {
            val y = top + plotHeight * i / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val allValues = values + secondaryValues
        var low = allValues.minOrNull() ?: 0.0
        var high = allValues.maxOrNull() ?: 1.0
        if (high - low < 1e-12) {
            val pad = max(abs(high) * 0.002, 0.000001)
            low -= pad
            high += pad
        } else {
            val pad = (high - low) * 0.12
            low -= pad
            high += pad
        }
        drawSmoothLine(canvas, values, left, right, top, bottom, low, high, linePaint, true)
        if (secondaryValues.size > 1) {
            drawSmoothLine(canvas, secondaryValues, left, right, top, bottom, low, high, secondaryPaint, false)
        }
    }

    private fun drawSmoothLine(
        canvas: Canvas,
        series: List<Double>,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        low: Double,
        high: Double,
        paint: Paint,
        showDot: Boolean,
    ) {
        if (series.size < 2) return
        val plotHeight = bottom - top
        val points = series.mapIndexed { index, value ->
            val x = left + (right - left) * index / (series.size - 1).toFloat()
            val y = bottom - ((value - low) / (high - low)).toFloat() * plotHeight
            Pair(x, y)
        }
        val path = Path().apply {
            moveTo(points.first().first, points.first().second)
            for (i in 1 until points.size) {
                val previous = points[i - 1]
                val current = points[i]
                val midX = (previous.first + current.first) / 2f
                val midY = (previous.second + current.second) / 2f
                quadTo(previous.first, previous.second, midX, midY)
            }
            lineTo(points.last().first, points.last().second)
        }
        canvas.drawPath(path, paint)
        if (showDot) canvas.drawCircle(points.last().first, points.last().second, 3.4f * density, dotPaint)
    }
}
