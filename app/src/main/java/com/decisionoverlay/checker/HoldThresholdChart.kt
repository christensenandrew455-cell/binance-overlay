package com.decisionoverlay.checker

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Native percentage-distance funnel: 15m on the left to 1m on the right.
 * Upper boundary = minimum +% gap required for a historically supported
 * YES-leading hold at its current live ask; lower = negative % for NO.
 * Missing thresholds are left BLANK (no invented / interpolated points).
 */
class HoldThresholdChart(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val labelSize = 10f * resources.displayMetrics.scaledDensity
    private val yesLine = Color.rgb(54, 212, 161)
    private val noLine = Color.rgb(255, 167, 92)
    private val axis = Color.rgb(125, 148, 168)
    private var yes: List<Double?> = List(15) { null }
    private var no: List<Double?> = List(15) { null }
    private var currentMinute = 15
    private var currentGap: Double? = null
    private var scenario = true

    init { minimumHeight = (270 * density).toInt() }

    fun show(
        historic: HoldProbabilityEngine.Historical,
        yesAsk: Double?, noAsk: Double?,
        minute: Int = 15, gapPct: Double? = null
    ) {
        scenario = yesAsk == null || noAsk == null
        yes = (15 downTo 1).map {
            historic.threshold(it, 1, yesAsk ?: .60)
        }
        no = (15 downTo 1).map {
            historic.threshold(it, -1, noAsk ?: .60)
        }
        currentMinute = minute.coerceIn(1, 15)
        currentGap = gapPct
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = (270 * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val left = 55f * density
        val right = w - 18f * density
        val top = 43f * density
        val bottom = h - 40f * density
        val middle = (top + bottom) / 2f
        fun x(slot: Int) = left + (15 - slot) / 14f * (right - left)

        val maxObserved = (yes + no).filterNotNull().maxOrNull() ?: 0.0
        val limit = max(0.25, max(maxObserved * 1.4, abs(currentGap ?: 0.0) * 1.3))
        fun y(gap: Double): Float =
            middle - (gap / limit).toFloat() * ((bottom - top) / 2f)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(220, 232, 242)
        paint.textSize = labelSize * 1.12f
        canvas.drawText("Minimum historical gap (% of target)", left, 17f * density, paint)
        paint.textSize = labelSize
        paint.color = axis
        canvas.drawText(if (scenario) "Illustrative 60c asks (not live quotes)"
            else "Current Kalshi BUY asks + estimated fees", left, 31f * density, paint)

        paint.color = Color.rgb(49, 65, 83)
        paint.strokeWidth = density
        for (scale in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
            val at = y(limit * scale)
            canvas.drawLine(left, at, right, at, paint)
            paint.color = axis
            canvas.drawText(String.format(Locale.US, "%+.2f%%", limit * scale),
                2f * density, at + labelSize / 3, paint)
            paint.color = Color.rgb(49, 65, 83)
        }
        for (minute in listOf(15, 10, 5, 1)) {
            canvas.drawLine(x(minute), top, x(minute), bottom, paint)
            paint.color = axis
            canvas.drawText(minute.toString() + "m", x(minute) - 7f * density,
                bottom + 16f * density, paint)
            paint.color = Color.rgb(49, 65, 83)
        }

        fun drawSeries(points: List<Double?>, color: Int, negative: Boolean) {
            paint.color = color
            paint.strokeWidth = 2.4f * density
            paint.style = Paint.Style.STROKE
            val segment = Path()
            var connected = false
            for ((index, value) in points.withIndex()) {
                val minute = 15 - index
                if (value == null) {
                    if (connected) canvas.drawPath(segment, paint)
                    segment.reset()
                    connected = false
                    continue
                }
                val py = y(if (negative) -value else value)
                if (!connected) segment.moveTo(x(minute), py)
                else segment.lineTo(x(minute), py)
                connected = true
            }
            if (connected) canvas.drawPath(segment, paint)
            paint.style = Paint.Style.FILL
            for ((index, value) in points.withIndex()) {
                if (value == null) continue
                val minute = 15 - index
                canvas.drawCircle(x(minute), y(if (negative) -value else value),
                    2.2f * density, paint)
            }
        }

        drawSeries(yes, yesLine, false)
        drawSeries(no, noLine, true)

        // Dashed vertical time cursor shows position in the current market.
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * density
        paint.color = Color.LTGRAY
        paint.pathEffect = android.graphics.DashPathEffect(
            floatArrayOf(4f * density, 4f * density), 0f)
        canvas.drawLine(x(currentMinute), top, x(currentMinute), bottom, paint)
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        currentGap?.let { v ->
            paint.color = Color.WHITE
            canvas.drawCircle(x(currentMinute), y(v), 5f * density, paint)
        }

        paint.textSize = labelSize
        paint.color = yesLine
        canvas.drawText("GREEN = YES threshold (+%)", left, h - 16f * density, paint)
        paint.color = noLine
        canvas.drawText("ORANGE = NO threshold (-%)", right - 140f * density,
            h - 16f * density, paint)
    }
}
