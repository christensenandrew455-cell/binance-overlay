package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

class CandleChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {
    private val candles = mutableListOf<Candle>()
    private val up = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF35C98A.toInt(); strokeWidth = 2f }
    private val down = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF647C.toInt(); strokeWidth = 2f }
    private val ema7Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFC857.toInt(); strokeWidth = 2.4f; style = Paint.Style.STROKE }
    private val ema25Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF55B8FF.toInt(); strokeWidth = 2.4f; style = Paint.Style.STROKE }
    private val ema99Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFCE93D8.toInt(); strokeWidth = 2.4f; style = Paint.Style.STROKE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334D607A; strokeWidth = 1f }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB7C2D2.toInt(); textSize = 10f }

    fun setCandles(values: List<Candle>) {
        candles.clear()
        candles.addAll(values.takeLast(150))
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val history = candles.toList()
        val data = history.takeLast(100)
        if (data.isEmpty()) {
            canvas.drawText("Waiting for 1-minute candles…", 14f, height / 2f, label)
            return
        }
        val left = 12f
        val right = width - 58f
        val top = 12f
        val bottom = height - 18f
        val high = data.maxOf { it.high }
        val low = data.minOf { it.low }
        val range = max(high - low, 0.0000001)
        fun y(value: Double) = bottom - ((value - low) / range).toFloat() * (bottom - top)
        repeat(4) { i ->
            val gy = top + (bottom - top) * i / 3f
            canvas.drawLine(left, gy, right, gy, grid)
            val value = high - range * i / 3.0
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", value), right + 4f, gy + 4f, label)
        }
        val slot = (right - left) / data.size
        val bodyWidth = max(1.5f, slot * 0.62f)
        data.forEachIndexed { index, candle ->
            val x = left + slot * (index + 0.5f)
            val paint = if (candle.close >= candle.open) up else down
            canvas.drawLine(x, y(candle.high), x, y(candle.low), paint)
            val bodyTop = min(y(candle.open), y(candle.close))
            val bodyBottom = max(y(candle.open), y(candle.close))
            canvas.drawRect(x - bodyWidth / 2, bodyTop, x + bodyWidth / 2, max(bodyTop + 1.5f, bodyBottom), paint)
        }
        val closes = history.map { it.close }
        val visibleStart = history.size - data.size
        drawEma(canvas, closes, 7, visibleStart, left, slot, ::y, ema7Paint)
        drawEma(canvas, closes, 25, visibleStart, left, slot, ::y, ema25Paint)
        drawEma(canvas, closes, 99, visibleStart, left, slot, ::y, ema99Paint)
        canvas.drawText("100 × 1m candles", left, height - 3f, label)
    }

    private fun drawEma(
        canvas: Canvas, values: List<Double>, period: Int, visibleStart: Int,
        left: Float, slot: Float, y: (Double) -> Float, paint: Paint
    ) {
        val series = Indicators.emaSeries(values, period)
        if (series.size < 2) return
        val path = Path()
        var started = false
        series.forEachIndexed { index, value ->
            val candleIndex = index + period - 1
            if (candleIndex >= visibleStart) {
                val x = left + slot * (candleIndex - visibleStart + 0.5f)
                if (!started) {
                    path.moveTo(x, y(value))
                    started = true
                } else {
                    path.lineTo(x, y(value))
                }
            }
        }
        if (started) canvas.drawPath(path, paint)
    }
}
