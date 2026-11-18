package com.decisionoverlay.checker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Minimal live view: target in gold, BTC trace in gold, symmetric 70% lines,
 * final three minutes boxed in white, a countdown, and numeric percentages.
 * No 60-candle history, buttons, candlesticks, or historical backtest UI.
 */
class BtcRangeBacktestActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var chart: BtcChart
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val redraw = object : Runnable {
        override fun run() {
            chart.invalidate()
            handler.postDelayed(this, 250L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.rgb(12, 16, 22)
        window.navigationBarColor = Color.rgb(12, 16, 22)
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN)
        chart = BtcChart(this)
        setContentView(chart)
        ContextCompat.startForegroundService(this,
            Intent(this, BtcRangeBacktestService::class.java)
                .setAction(BtcRangeBacktestService.START))
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) !=
                    PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(redraw)
        handler.post(redraw)
    }

    override fun onPause() {
        handler.removeCallbacks(redraw)
        super.onPause()
    }

    private class BtcChart(context: Context) : View(context) {
        private val background = Color.rgb(12, 16, 22)
        private val gold = Color.rgb(242, 190, 74)
        private val green = Color.rgb(54, 214, 113)
        private val red = Color.rgb(243, 77, 84)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val minute = 60_000L
        private val quarter = 15L * minute
        private fun dp(value: Float) = value * resources.displayMetrics.density

        private fun line(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float,
                         color: Int, thickness: Float) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(thickness)
            paint.color = color
            c.drawLine(x0, y0, x1, y1, paint)
        }
        private fun label(c: Canvas, s: String, x: Float, y: Float,
                          color: Int, font: Float = 12f) {
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL)
            paint.textSize = dp(font)
            paint.color = color
            c.drawText(s, x, y, paint)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            canvas.drawColor(background)
            val now = System.currentTimeMillis()
            val start = now / quarter * quarter
            val remainingMs = (start + quarter - now).coerceIn(0L, quarter)
            // Ceiling rounding carries a second into the previous minute.
            val displaySeconds = (remainingMs + 999L) / 1000L
            val time = String.format(Locale.US, "%02d:%02d",
                displaySeconds / 60, displaySeconds % 60)
            val w = width.toFloat()
            val h = height.toFloat()
            // Keep the actual plotting area square, including on tall phones.
            val left = dp(55f)
            val side = minOf(w - left - dp(18f), h - dp(124f))
            if (side <= 0f) return
            val right = left + side
            val top = (h - side) / 2f
            val bottom = top + side
            val mid = (top + bottom) / 2f
            val half = side / 2f

            paint.textAlign = Paint.Align.RIGHT
            label(canvas, time, w - dp(18f), dp(47f), Color.WHITE, 26f)
            paint.textAlign = Paint.Align.LEFT

            val snapshot = try {
                JSONObject(context.getSharedPreferences(
                    BtcRangeBacktestService.PREFS, Context.MODE_PRIVATE)
                    .getString(BtcRangeBacktestService.KEY_SNAPSHOT, "{}") ?: "{}")
            } catch (_: Exception) { JSONObject() }
            val valid = snapshot.optLong("section", -1L) == start &&
                snapshot.optDouble("target", 0.0) > 0.0 &&
                snapshot.optDouble("acm", 0.0) > 0.0
            // Old session vanishes immediately on the exact quarter-hour.
            if (!valid) {
                line(canvas, left, mid, right, mid, gold, 1.8f)
                return
            }
            val target = snapshot.getDouble("target")
            val acm = snapshot.getDouble("acm")
            val history = snapshot.optJSONArray("points")
            val latest = snapshot.optDouble("latest", target)
            val largestGap = max(acm * 15.0, abs((latest - target) /
                target * 100.0) * 1.1).coerceAtLeast(0.0000001)
            val scale = half * 0.89f / largestGap
            fun yFor(gapPct: Double): Float =
                mid - (gapPct * scale).toFloat()
            fun xFor(ms: Long): Float =
                left + (ms - start).toFloat() / quarter * (right - left)
            fun guardY(y: Float) = y.coerceIn(top, bottom)

            // Thin gray guides on both sides: 60, 70, 80 and 90.
            // These follow the same 60-candle calculation as the main band.
            fun grayGuide(level: Int, direction: Double): Path {
                val path = Path()
                val fraction = (level - 50.0) / 40.0
                for (i in 0..60) {
                    val x = left + (right - left) * (i / 60f)
                    val minutesLeft = 15.0 * (1.0 - i / 60.0)
                    val y = guardY(yFor(direction * acm * minutesLeft * fraction))
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                return path
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1f)
            paint.color = Color.rgb(115, 121, 130)
            for (level in intArrayOf(60, 70, 80, 90)) {
                canvas.drawPath(grayGuide(level, 1.0), paint)
                canvas.drawPath(grayGuide(level, -1.0), paint)
            }

            // White final-three-minute area: independent boxes above and
            // below the target line, matching both YES and NO cases.
            val boxLeft = xFor(start + 12L * minute)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.3f)
            paint.color = Color.WHITE
            canvas.drawRoundRect(RectF(boxLeft, top, right, mid),
                dp(3f), dp(3f), paint)
            canvas.drawRoundRect(RectF(boxLeft, mid, right, bottom),
                dp(3f), dp(3f), paint)

            // Fixed custom percentage level 71: 21/40 of the remaining
            // distance between the user's 50% target and 90% ceiling.
            // ACM60 is hidden; these lines move toward target as time runs out.
            val fraction = (70.0 - 50.0) / 40.0
            val up = Path()
            val down = Path()
            for (i in 0..60) {
                val elapsed = 15.0 * i / 60
                val leftMinutes = 15.0 - elapsed
                val thresholdGapPct = acm * leftMinutes * fraction
                val x = left + (right - left) * (i / 60f)
                val upperY = guardY(yFor(thresholdGapPct))
                val lowerY = guardY(yFor(-thresholdGapPct))
                if (i == 0) {
                    up.moveTo(x, upperY)
                    down.moveTo(x, lowerY)
                } else {
                    up.lineTo(x, upperY)
                    down.lineTo(x, lowerY)
                }
            }
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.2f)
            paint.color = green
            canvas.drawPath(up, paint)
            paint.color = red
            canvas.drawPath(down, paint)
            line(canvas, left, mid, right, mid, gold, 1.8f)

            paint.textAlign = Paint.Align.LEFT
            for (level in intArrayOf(90, 80, 70, 60)) {
                val amount = acm * 15.0 * (level - 50.0) / 40.0
                val aboveColor = if (level == 70) green else Color.LTGRAY
                val belowColor = if (level == 70) red else Color.LTGRAY
                label(canvas, "${level}%", dp(3f), yFor(amount) + dp(4f),
                    aboveColor, 10f)
                label(canvas, "${level}%", dp(3f), yFor(-amount) + dp(4f),
                    belowColor, 10f)
            }
            label(canvas, "50%", dp(3f), mid + dp(4f), gold, 10f)

            // Plot only the current 15-minute section, as a smooth line.
            if (history != null && history.length() > 0) {
                val pts = ArrayList<Pair<Float, Float>>()
                for (i in 0 until history.length()) {
                    val p = history.optJSONArray(i) ?: continue
                    val t = p.optLong(0, -1L)
                    val price = p.optDouble(1, 0.0)
                    if (t !in start..now || price <= 0.0) continue
                    pts.add(xFor(t) to guardY(yFor((price - target) / target * 100.0)))
                }
                if (pts.isNotEmpty()) {
                    val path = Path()
                    path.moveTo(pts[0].first, pts[0].second)
                    for (i in 1 until pts.size) {
                        val a = pts[i - 1]
                        val b = pts[i]
                        val mx = (a.first + b.first) / 2f
                        val my = (a.second + b.second) / 2f
                        if (i == 1) path.lineTo(mx, my)
                        else path.quadTo(a.first, a.second, mx, my)
                    }
                    val last = pts.last()
                    path.lineTo(last.first, last.second)
                    paint.color = gold
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(2.8f)
                    canvas.drawPath(path, paint)
                    paint.style = Paint.Style.FILL
                    canvas.drawCircle(last.first, last.second, dp(4.8f), paint)
                    paint.color = Color.WHITE
                    canvas.drawCircle(last.first, last.second, dp(1.5f), paint)
                }
            }
        }
    }
}
