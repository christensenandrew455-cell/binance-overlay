package com.decisionoverlay.checker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** The entire app opens to this ONE live screen. The old testing menus are gone. */
class BtcAcmLiveActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var chart: BtcAcmTimeChart
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val updater = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 12, 16, 18)
            setBackgroundColor(Color.rgb(16, 21, 29))
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setTextIsSelectable(true); setPadding(0, 5, 0, 7)
        }
        panel.addView(label("BTC / KALSHI — LIVE ACM60", 21f))
        panel.addView(label(
            "60 completed BTC candles + 15m countdown curves. " +
            "The green/red limits shrink toward the yellow target as time runs out. " +
            "Curves recalculate using the latest ACM; dashed = forward projection. " +
            "Experimental K 0.25 / 60–69¢ alert rule; no orders placed.", 12f))
        chart = BtcAcmTimeChart(this)
        panel.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 490f).toInt()))
        status = label("Press START to monitor BTC and Kalshi.", 16f)
        details = label("", 13f)
        panel.addView(status)
        start = Button(this).apply {
            text = "START LIVE BUY ALERTS"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@BtcAcmLiveActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(this@BtcAcmLiveActivity,
                    Intent(this@BtcAcmLiveActivity,
                        BtcAcmLiveService::class.java).setAction(BtcAcmLiveService.START))
                refresh()
            }
        }
        stop = Button(this).apply {
            text = "STOP ALERTS"
            setOnClickListener {
                startService(Intent(this@BtcAcmLiveActivity,
                    BtcAcmLiveService::class.java).setAction(BtcAcmLiveService.STOP))
            }
        }
        panel.addView(start)
        panel.addView(stop)
        panel.addView(details)
        panel.addView(Button(this).apply {
            text = "COPY CURRENT BTC / KALSHI DATA"
            setOnClickListener {
                val data = getSharedPreferences(BtcAcmLiveService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (data.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("BTC ACM live", data))
            }
        })
        setContentView(ScrollView(this).apply { addView(panel) })
        refresh()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(updater)
        handler.post(updater)
    }

    override fun onPause() {
        handler.removeCallbacks(updater)
        super.onPause()
    }

    private fun refresh() {
        if (!::status.isInitialized) return
        val p = getSharedPreferences(BtcAcmLiveService.PREFS, MODE_PRIVATE)
        val state = p.getString("phase", "idle")
        status.text = p.getString("status", "Press START to monitor.")
        details.text = p.getString("report", "")
        start.isEnabled = state != "starting" && state != "running"
        stop.isEnabled = state == "starting" || state == "running"
        chart.load(
            p.getString("chart", "").orEmpty(),
            p.getString("curve", "").orEmpty(),
            p.getFloat("target", 0f).toDouble(),
            p.getFloat("spot", 0f).toDouble())
    }
}

/**
 * TOP: rolling 60 completed OHLC candle chart.
 * BOTTOM: 15-minute clock x-axis, historical 60-candle ACM bands
 *         and future projected convergence. No flat trigger lines.
 */
class BtcAcmTimeChart(context: android.content.Context) : View(context) {
    private data class Curve(
        val at: Long, val up: Double, val down: Double,
        val btc: Double?, val projected: Boolean
    )
    private var candles = emptyList<BtcAcmMath.Candle>()
    private var curves = emptyList<Curve>()
    private var target = 0.0
    private var spot = 0.0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val white = Color.rgb(226, 234, 242)
    private val dim = Color.rgb(140, 158, 178)
    private val upColor = Color.rgb(54, 215, 143)
    private val downColor = Color.rgb(245, 113, 119)
    private val yellow = Color.rgb(250, 199, 94)
    private val bg = Color.rgb(22, 31, 43)
    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = density * value

    fun load(bars: String, curve: String, t: Double, live: Double) {
        candles = try {
            val j = JSONArray(bars)
            (0 until j.length()).map { i ->
                val a = j.getJSONArray(i)
                BtcAcmMath.Candle(a.getLong(0), a.getDouble(1),
                    a.getDouble(2), a.getDouble(3), a.getDouble(4))
            }.filter { it.valid() }.takeLast(60)
        } catch (_: Exception) { emptyList() }
        curves = try {
            val j = JSONArray(curve)
            (0 until j.length()).map { i ->
                val a = j.getJSONArray(i)
                Curve(a.getLong(0), a.getDouble(1), a.getDouble(2),
                    if (a.isNull(3)) null else a.getDouble(3),
                    a.getBoolean(4))
            }
        } catch (_: Exception) { emptyList() }
        target = t; spot = live
        invalidate()
    }

    private fun text(canvas: Canvas, s: String, x: Float, y: Float,
        color: Int = white, size: Float = 12f) {
        paint.style = Paint.Style.FILL
        paint.color = color
        paint.textSize = dp(size)
        paint.strokeWidth = dp(1f)
        paint.pathEffect = null
        canvas.drawText(s, x, y, paint)
    }
    private fun grid(canvas: Canvas, l: Float, r: Float, top: Float, b: Float) {
        paint.color = Color.rgb(48, 62, 78)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(0.65f)
        paint.pathEffect = null
        for (i in 1..4) {
            val y = top + (b - top) * i / 5f
            canvas.drawLine(l, y, r, y, paint)
        }
        paint.style = Paint.Style.FILL
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bg)
        val left = dp(11f)
        val right = width.toFloat() - dp(12f)
        val top1 = dp(32f)
        val bottom1 = height * 0.43f
        val top2 = height * 0.56f
        val bottom2 = height - dp(46f)
        text(canvas, "LAST 60 COMPLETED 1-MIN CANDLES", left, dp(21f), white, 13f)
        grid(canvas, left, right, top1, bottom1)
        drawCandles(canvas, left, right, top1, bottom1)
        paint.color = Color.rgb(63, 77, 91)
        paint.strokeWidth = dp(1f)
        canvas.drawLine(left, height * 0.47f, right, height * 0.47f, paint)
        text(canvas, "15-MINUTE TIME CURVE — CONVERGES AT EXPIRY",
            left, height * 0.54f, white, 12f)
        grid(canvas, left, right, top2, bottom2)
        drawTimeCurve(canvas, left, right, top2, bottom2)
    }

    private fun drawCandles(canvas: Canvas, l: Float, r: Float, top: Float,
        b: Float) {
        if (candles.isEmpty()) {
            text(canvas, "Waiting for BTC candle data", l, (top + b)/2, dim)
            return
        }
        val priceLow = candles.minOf { it.low }
        val priceHigh = candles.maxOf { it.high }
        val spread = max(priceHigh-priceLow, 1.0)
        val minP = priceLow - spread*0.07
        val maxP = priceHigh + spread*0.07
        fun y(price: Double): Float =
            (b - ((price - minP)/(maxP-minP)*(b-top))).toFloat()
        val step = (r-l)/candles.size
        for ((i,c) in candles.withIndex()) {
            val cx = l + (i + 0.5f)*step
            paint.color = if (c.close >= c.open) upColor else downColor
            paint.pathEffect = null
            paint.strokeWidth = dp(1.1f)
            canvas.drawLine(cx, y(c.high), cx, y(c.low), paint)
            paint.style = Paint.Style.FILL
            canvas.drawRect(cx-step*0.3f,
                min(y(c.open),y(c.close)),
                cx+step*0.3f,max(y(c.open),y(c.close))+dp(1f),paint)
        }
        if (target in minP..maxP) {
            paint.color = yellow
            paint.strokeWidth = dp(1f)
            paint.pathEffect = DashPathEffect(floatArrayOf(dp(4f), dp(4f)), 0f)
            canvas.drawLine(l, y(target), r, y(target), paint)
            paint.pathEffect = null
        }
        text(canvas, String.format(Locale.US, "BTC $%,.2f",spot),
            l, b + dp(18f), white, 12f)
        text(canvas, String.format(Locale.US, "Target $%,.2f",target),
            r - dp(146f), b + dp(18f), yellow, 12f)
    }

    private fun drawTimeCurve(canvas: Canvas,l: Float,r: Float,top: Float,b: Float) {
        if (curves.size < 2 || target <= 0.0) {
            text(canvas, "Waiting for 15-minute market", l, (top+b)/2, dim)
            return
        }
        val closeAt = curves.last().at
        val openAt = closeAt - 15L * BtcAcmMath.MINUTE_MS
        val prices = curves.flatMap { listOf(it.up,it.down,it.btc ?: target) } +
            listOf(target,spot)
        val pMin = prices.minOrNull() ?: return
        val pMax = prices.maxOrNull() ?: return
        val span = max(pMax-pMin, 1.0)
        val minP = pMin-span*0.12
        val maxP = pMax+span*0.12
        fun x(at: Long): Float =
            l + ((at-openAt).toDouble()/(closeAt-openAt).toDouble())
                .toFloat()*(r-l)
        fun y(price: Double): Float =
            (b - (price-minP)/(maxP-minP)*(b-top)).toFloat()
        paint.color = yellow
        paint.strokeWidth = dp(1.1f)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(5f),dp(3f)),0f)
        canvas.drawLine(l,y(target),r,y(target),paint)
        paint.pathEffect = null
        for (i in 0 until curves.size-1) {
            val a = curves[i]
            val next = curves[i+1]
            val dash = next.projected
            paint.pathEffect = if (dash)
                DashPathEffect(floatArrayOf(dp(4f),dp(3f)),0f)
                else null
            paint.strokeWidth = dp(if (dash) 1.6f else 2f)
            paint.color = upColor
            canvas.drawLine(x(a.at),y(a.up),x(next.at),y(next.up),paint)
            paint.color = downColor
            canvas.drawLine(x(a.at),y(a.down),x(next.at),y(next.down),paint)
            if (a.btc != null && next.btc != null && !dash) {
                paint.color = white
                paint.strokeWidth = dp(1.7f)
                canvas.drawLine(x(a.at),y(a.btc),x(next.at),y(next.btc),paint)
            }
        }
        paint.pathEffect = null
        val live = curves.lastOrNull { !it.projected } ?: return
        paint.color = dim
        paint.strokeWidth = dp(1f)
        paint.pathEffect = DashPathEffect(floatArrayOf(dp(3f),dp(4f)),0f)
        canvas.drawLine(x(live.at),top,x(live.at),b,paint)
        paint.pathEffect = null
        paint.color = white
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x(live.at),y(spot),dp(4f),paint)
        text(canvas, "15:00 LEFT",l,b+dp(18f),dim,11f)
        val remaining = max(0L,closeAt-live.at)/1000
        val countdown = String.format(Locale.US,"NOW %d:%02d",remaining/60,remaining%60)
        text(canvas,countdown,
            max(l,min(x(live.at)-dp(32f),r-dp(75f))), b+dp(18f),white,11f)
        text(canvas,"0:00",r-dp(29f),b+dp(18f),dim,11f)
        text(canvas,"GREEN = YES above   RED = NO below   WHITE = BTC",
            l,b+dp(37f),white,10f)
    }
}
