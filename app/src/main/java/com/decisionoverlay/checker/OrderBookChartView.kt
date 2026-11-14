package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/** Displays five-second averages of proximity-weighted top-10 bid and ask liquidity. */
class OrderBookChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val density = resources.displayMetrics.density
    private val card = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(26, 36, 53) }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 154, 164, 181)
        strokeWidth = density
    }
    private val bidPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(45, 202, 158)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val askPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(239, 83, 101)
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(154, 164, 181)
        textSize = 10f * density
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
    }
    private var bids: List<Double> = emptyList()
    private var asks: List<Double> = emptyList()

    fun setSeries(bidNotional: List<Double>, askNotional: List<Double>) {
        bids = bidNotional.filter { it.isFinite() }.takeLast(60)
        asks = askNotional.filter { it.isFinite() }.takeLast(60)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 12f * density
        val right = width - 12f * density
        val top = 27f * density
        val bottom = height - 12f * density
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 11f * density, 11f * density, card)
        canvas.drawText("SMOOTHED • NEAR-PRICE LIQUIDITY", left, 17f * density, titlePaint)
        if (right <= left || bottom <= top || bids.size < 2 || asks.size < 2) {
            canvas.drawText("Collecting live order-book samples…", left, (top + bottom) / 2f, titlePaint)
            return
        }
        for (i in 0..3) {
            val y = top + (bottom - top) * i / 3f
            canvas.drawLine(left, y, right, y, grid)
        }
        val all = bids + asks
        var low = all.minOrNull() ?: 0.0
        var high = all.maxOrNull() ?: 1.0
        if (high - low < 1.0) {
            val pad = maxOf(abs(high) * 0.02, 1.0)
            low -= pad
            high += pad
        } else {
            val pad = (high - low) * 0.12
            low -= pad
            high += pad
        }
        drawSeries(canvas, bids, left, right, top, bottom, low, high, bidPaint)
        drawSeries(canvas, asks, left, right, top, bottom, low, high, askPaint)
        titlePaint.color = bidPaint.color
        canvas.drawText("BIDS", left, bottom - 2f * density, titlePaint)
        titlePaint.color = askPaint.color
        canvas.drawText("ASKS", left + 40f * density, bottom - 2f * density, titlePaint)
        titlePaint.color = Color.rgb(154, 164, 181)
    }

    private fun drawSeries(
        canvas: Canvas, series: List<Double>, left: Float, right: Float,
        top: Float, bottom: Float, low: Double, high: Double, paint: Paint,
    ) {
        if (series.size < 2) return
        val path = Path()
        series.forEachIndexed { index, value ->
            val x = left + (right - left) * index / (series.size - 1).toFloat()
            val y = bottom - ((value - low) / (high - low)).toFloat() * (bottom - top)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
