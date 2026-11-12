package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Color
import android.graphics.DashPathEffect
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
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 196, 92)
        strokeWidth = 1.6f * density
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val zeroPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 220, 225, 235)
        strokeWidth = 1.2f * density
        pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)
    }
    private val positivePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(52, 211, 153)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val negativePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(248, 92, 92)
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val spikePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 190, 75)
        strokeWidth = 3f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 36, 53) }
    private val peakTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }

    private enum class Mode { LINES, HEARTBEAT, MOMENTUM }
    private var mode = Mode.LINES
    private var chartTitle = "EMA LINE"
    private var values: List<Double> = emptyList()
    private var secondaryValues: List<Double> = emptyList()
    private var lineColor = Color.CYAN
    private var secondaryColor = Color.WHITE
    private var targetPrice: Double? = null
    private var baselineValues: List<Double> = emptyList()
    private var spikeValues: List<Double?> = emptyList()
    private var volumeValues: List<Double> = emptyList()
    private var volumeUpValues: List<Boolean> = emptyList()

    fun setSeries(title: String, series: List<Double>, color: Int) {
        setSeries(title, series, color, emptyList(), Color.WHITE)
    }

    fun setSeries(title: String, series: List<Double>, color: Int, secondSeries: List<Double>, secondColor: Int) {
        mode = Mode.LINES
        chartTitle = title
        values = series.filter { it.isFinite() }.takeLast(60)
        secondaryValues = secondSeries.filter { it.isFinite() }.takeLast(60)
        targetPrice = null
        lineColor = color
        secondaryColor = secondColor
        linePaint.color = lineColor
        secondaryPaint.color = secondaryColor
        dotPaint.color = lineColor
        invalidate()
    }

    fun setPriceSeries(title: String, ema7: List<Double>, ema99: List<Double>, target: Double?) {
        setSeries(title, ema7, Color.rgb(85, 214, 190), ema99, Color.rgb(194, 139, 250))
        targetPrice = target?.takeIf { it.isFinite() }
        invalidate()
    }

    fun setHeartbeat(title: String, volumes: List<Double>, spikes: List<Double?>, candleUp: List<Boolean>) {
        mode = Mode.HEARTBEAT
        chartTitle = title
        volumeValues = volumes.takeLast(60)
        spikeValues = spikes.takeLast(60)
        volumeUpValues = candleUp.takeLast(60)
        baselineValues = emptyList()
        values = emptyList()
        secondaryValues = emptyList()
        invalidate()
    }

    fun setMomentum(title: String, series: List<Double>) {
        mode = Mode.MOMENTUM
        chartTitle = title
        values = series.filter { it.isFinite() }.takeLast(60)
        secondaryValues = emptyList()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 12f * density
        val right = width - 12f * density
        val top = 27f * density
        val bottom = height - 12f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 11f * density, 11f * density, cardPaint)
        canvas.drawText(chartTitle, left, 17f * density, titlePaint)
        val plotHeight = bottom - top
        if (right <= left || plotHeight <= 0f) return
        for (i in 0..3) {
            val y = top + plotHeight * i / 3f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        when (mode) {
            Mode.LINES -> drawLines(canvas, left, right, top, bottom)
            Mode.HEARTBEAT -> drawHeartbeat(canvas, left, right, top, bottom)
            Mode.MOMENTUM -> drawMomentum(canvas, left, right, top, bottom)
        }
    }

    private fun drawLines(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (values.size < 2) {
            drawWaiting(canvas, left, top, bottom)
            return
        }
        val all = values + secondaryValues + listOfNotNull(targetPrice)
        var low = all.minOrNull() ?: 0.0
        var high = all.maxOrNull() ?: 1.0
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
        if (secondaryValues.size > 1) drawSmoothLine(canvas, secondaryValues, left, right, top, bottom, low, high, secondaryPaint, false)
        targetPrice?.let { target ->
            val y = yFor(target, top, bottom, low, high)
            canvas.drawLine(left, y, right, y, targetPaint)
            peakTextPaint.color = targetPaint.color
            canvas.drawText("TARGET ${fmtPrice(target)}", left + 5f * density, (y - 4f * density).coerceAtLeast(top + 10f * density), peakTextPaint)
        }
    }

    private fun drawHeartbeat(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (volumeValues.isEmpty()) {
            drawWaiting(canvas, left, top, bottom)
            return
        }
        val plotHeight = bottom - top
        val baseY = bottom - plotHeight * 0.08f
        canvas.drawLine(left, baseY, right, baseY, zeroPaint)
        val maxVolume = volumeValues.maxOrNull()?.coerceAtLeast(0.0000001) ?: 1.0
        val count = volumeValues.size
        val slotWidth = (right - left) / count.toFloat()
        val barWidth = (slotWidth * 0.58f).coerceAtLeast(2f * density)
        volumeValues.forEachIndexed { index, volume ->
            val x = left + slotWidth * (index + 0.5f)
            val isSpike = spikeValues.getOrNull(index) != null
            // Keep ordinary candles visible as tiny blocks; unusual volume gets a taller pulse.
            val relative = (volume / maxVolume).coerceIn(0.0, 1.0)
            val normalHeight = (2.5f * density + relative.toFloat() * plotHeight * 0.10f)
            val spikeHeight = (plotHeight * (0.18f + relative.toFloat() * 0.68f))
            val barHeight = if (isSpike) spikeHeight else normalHeight
            val up = volumeUpValues.getOrNull(index) ?: true
            val color = if (up) Color.rgb(22, 190, 130) else Color.rgb(239, 75, 98)
            markerPaint.color = color
            val rect = android.graphics.RectF(
                x - barWidth / 2f,
                baseY - barHeight,
                x + barWidth / 2f,
                baseY
            )
            canvas.drawRoundRect(rect, 1.5f * density, 1.5f * density, markerPaint)
        }
        peakTextPaint.color = Color.rgb(255, 190, 75)
        canvas.drawText("GREEN = UP CANDLE  •  RED = DOWN CANDLE  •  TALL = HIGH VOLUME", left, top + 11f * density, peakTextPaint)
    }

    private fun drawMomentum(canvas: Canvas, left: Float, right: Float, top: Float, bottom: Float) {
        if (values.isEmpty()) {
            drawWaiting(canvas, left, top, bottom)
            return
        }
        val maxAbs = max(values.maxOf { abs(it) }, 0.000001)
        // Leave headroom like an exchange MACD panel instead of stretching every move
        // to the full chart height.
        val bound = maxAbs * 2.8
        val zeroY = yFor(0.0, top, bottom, -bound, bound)
        val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(210, 190, 198, 210)
            strokeWidth = 1.2f * density
        }
        canvas.drawLine(left, zeroY, right, zeroY, baselinePaint)

        val count = values.size
        val slotWidth = (right - left) / count.toFloat()
        val barWidth = (slotWidth * 0.62f).coerceAtLeast(2f * density)
        values.forEachIndexed { index, value ->
            val x = left + slotWidth * (index + 0.5f)
            val valueY = yFor(value, top, bottom, -bound, bound)
            val previous = values.getOrNull(index - 1) ?: value
            val rising = value > previous
            val color = when {
                value >= 0.0 && rising -> Color.rgb(45, 202, 158)
                value >= 0.0 -> Color.rgb(91, 151, 133)
                !rising -> Color.rgb(239, 83, 101)
                else -> Color.rgb(164, 91, 105)
            }
            markerPaint.color = color
            val rect = android.graphics.RectF(
                x - barWidth / 2f,
                minOf(zeroY, valueY),
                x + barWidth / 2f,
                maxOf(zeroY, valueY)
            )
            canvas.drawRoundRect(rect, 0.8f * density, 0.8f * density, markerPaint)
        }
        peakTextPaint.color = titlePaint.color
        canvas.drawText("MACD HISTOGRAM  •  12 / 26 / 9", left, top + 11f * density, peakTextPaint)
    }

    private fun drawMajorPeaks(canvas: Canvas, points: List<Pair<Float, Float>>, top: Float, bottom: Float) {
        if (values.size < 5) return
        val magnitudes = values.map { abs(it) }.sorted()
        val threshold = magnitudes[(magnitudes.size * 0.80).toInt().coerceIn(0, magnitudes.lastIndex)]
        for (i in 1 until values.lastIndex) {
            val value = values[i]
            if (abs(value) < threshold) continue
            val isPeak = if (value >= 0.0) value >= values[i - 1] && value > values[i + 1]
                else value <= values[i - 1] && value < values[i + 1]
            if (!isPeak) continue
            val point = points[i]
            val color = if (value >= 0.0) positivePaint.color else negativePaint.color
            markerPaint.color = color
            canvas.drawCircle(point.first, point.second, 4f * density, markerPaint)
            peakTextPaint.color = color
            val label = if (value >= 0.0) "GREEN PEAK" else "RED PEAK"
            val labelY = if (value >= 0.0) (point.second - 8f * density).coerceAtLeast(top + 10f * density)
                else (point.second + 14f * density).coerceAtMost(bottom - 2f * density)
            canvas.drawText(label, point.first + 4f * density, labelY, peakTextPaint)
        }
    }

    private fun drawWaiting(canvas: Canvas, left: Float, top: Float, bottom: Float) {
        titlePaint.textSize = 11f * density
        canvas.drawText("Waiting for candle history…", left, top + (bottom - top) / 2f, titlePaint)
        titlePaint.textSize = 10f * density
    }

    private fun yFor(value: Double, top: Float, bottom: Float, low: Double, high: Double): Float =
        bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)

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
        val points = series.mapIndexed { index, value ->
            val x = left + (right - left) * index / (series.size - 1).toFloat()
            Pair(x, yFor(value, top, bottom, low, high))
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
        if (showDot) {
            dotPaint.color = paint.color
            canvas.drawCircle(points.last().first, points.last().second, 3.4f * density, dotPaint)
        }
    }
}
