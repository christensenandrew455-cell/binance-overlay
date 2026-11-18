package com.decisionoverlay.checker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class BtcAcmLiveActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var details: TextView
    private lateinit var chart: BtcAcmCandleChart
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val updater = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 16, 20, 22)
            setBackgroundColor(Color.rgb(16, 21, 29))
        }
        fun label(value: String, size: Float): TextView = TextView(this).apply {
            text = value; textSize = size; setTextColor(Color.WHITE)
            setTextIsSelectable(true); setPadding(0, 9, 0, 9)
        }
        root.addView(label("BTC — LIVE 60-CANDLE ACM / KALSHI BUY ALERT", 19f))
        root.addView(label(
            "The last 60 COMPLETED 1-minute BTC candles set the ACM. " +
            "The chart shows the current spot, Kalshi target and +/− trigger lines. " +
            "Rule: ACM60 × minutes remaining × 0.25. " +
            "BUY notification only when the Kalshi YES/NO ask is 60–69¢, " +
            "the BTC threshold is reached, and fee-adjusted breakeven is " +
            "at least 5 percentage points below the 21/27 historical sample. " +
            "No alert in the final minute. No orders are placed. " +
            "Binance.US spot is NOT Kalshi's settlement index.", 13f))
        chart = BtcAcmCandleChart(this)
        root.addView(chart, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density * 315f).toInt()
        ))
        start = Button(this).apply {
            text = "START LIVE BTC + KALSHI MONITOR"
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
            text = "STOP LIVE MONITOR"
            setOnClickListener {
                startService(Intent(this@BtcAcmLiveActivity,
                    BtcAcmLiveService::class.java).setAction(BtcAcmLiveService.STOP))
            }
        }
        root.addView(start); root.addView(stop)
        root.addView(Button(this).apply {
            text = "COPY LIVE SIGNAL & PRICES"
            setOnClickListener {
                val value = getSharedPreferences(BtcAcmLiveService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (value.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Live BTC ACM / Kalshi", value))
            }
        })
        root.addView(Button(this).apply {
            text = "BACK TO HISTORICAL TESTS"
            setOnClickListener {
                startActivity(Intent(this@BtcAcmLiveActivity,
                    BtcAcmBacktestActivity::class.java))
            }
        })
        status = label("Start monitoring to get live values.", 15f)
        details = label("", 13f)
        root.addView(status); root.addView(details)
        setContentView(ScrollView(this).apply { addView(root) })
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
        val phase = p.getString("phase", "idle")
        status.text = p.getString("status", "Press START to monitor BTC and Kalshi.")
        details.text = p.getString("report", "")
        start.isEnabled = phase != "running" && phase != "starting"
        stop.isEnabled = phase == "running" || phase == "starting"
        chart.load(p.getString("chart", "").orEmpty(),
            p.getFloat("target", 0f).toDouble(),
            p.getFloat("up", 0f).toDouble(),
            p.getFloat("down", 0f).toDouble(),
            p.getFloat("spot", 0f).toDouble())
    }
}

class BtcAcmCandleChart(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var candles = emptyList<BtcAcmMath.Candle>()
    private var target = Double.NaN
    private var up = Double.NaN
    private var down = Double.NaN
    private var spot = Double.NaN
    private val light = Color.rgb(226, 232, 240)
    private val dim = Color.rgb(118, 134, 154)
    private val green = Color.rgb(63, 214, 143)
    private val red = Color.rgb(247, 111, 117)

    fun load(raw: String, newTarget: Double, newUp: Double, newDown: Double,
        newSpot: Double) {
        candles = try {
            val data = JSONArray(raw)
            (0 until data.length()).map { i ->
                val row = data.getJSONArray(i)
                BtcAcmMath.Candle(row.getLong(0),
                    row.getDouble(1), row.getDouble(2),
                    row.getDouble(3), row.getDouble(4))
            }.filter { it.valid() }.takeLast(60)
        } catch (_: Exception) { emptyList() }
        target = newTarget; up = newUp; down = newDown; spot = newSpot
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(21, 29, 40))
        paint.style = Paint.Style.FILL
        paint.strokeWidth = 1f
        paint.color = light
        paint.textSize = 30f
        canvas.drawText("BTC / USDT — last 60 completed 1m candles", 16f, 32f, paint)
        paint.textSize = 22f
        if (candles.isEmpty()) {
            paint.color = dim
            canvas.drawText("Waiting for 60 complete candles…", 16f, height / 2f, paint)
            return
        }
        val left = 16f
        val right = width - 20f
        val top = 54f
        val bottom = height - 62f
        val values = candles.flatMap { listOf(it.high, it.low) }.toMutableList()
        for (v in listOf(target, up, down, spot))
            if (v.isFinite() && v > 0) values.add(v)
        if (values.isEmpty()) return
        val low = values.minOrNull() ?: return
        val high = values.maxOrNull() ?: return
        val span = max(high - low, 1.0)
        val minY = low - span * 0.08
        val maxY = high + span * 0.08
        fun y(value: Double): Float =
            (bottom - (value - minY) / (maxY - minY) * (bottom - top)).toFloat()
        val step = (right - left) / max(1, candles.size).toFloat()
        paint.color = Color.rgb(42, 55, 70)
        paint.strokeWidth = 1f
        for (i in 1..4) {
            val yy = top + (bottom - top) * i / 5f
            canvas.drawLine(left, yy, right, yy, paint)
        }
        for ((i, c) in candles.withIndex()) {
            val x = left + step * (i + 0.5f)
            val rising = c.close >= c.open
            paint.color = if (rising) green else red
            paint.strokeWidth = 2f
            canvas.drawLine(x, y(c.high), x, y(c.low), paint)
            val half = max(step * 0.32f, 1f)
            paint.style = Paint.Style.FILL
            canvas.drawRect(x-half, min(y(c.open), y(c.close)),
                x+half, max(y(c.open), y(c.close)) + 1.5f, paint)
        }
        fun marker(price: Double, color: Int) {
            if (!price.isFinite() || price <= 0) return
            val yy = y(price)
            paint.color = color
            paint.strokeWidth = 2f
            canvas.drawLine(left, yy, right, yy, paint)
        }
        marker(up, Color.rgb(43, 149, 95))
        marker(down, Color.rgb(157, 60, 68))
        marker(target, Color.rgb(250, 198, 92))
        if (spot.isFinite() && spot > 0) {
            paint.color = light
            canvas.drawCircle(right - 4f, y(spot), 5f, paint)
        }
        paint.textSize = 19f
        paint.color = Color.rgb(250, 198, 92)
        canvas.drawText(String.format(Locale.US, "Target $%,.2f", target), left, height-34f, paint)
        paint.color = green
        canvas.drawText("UP K0.25", left, height-9f, paint)
        paint.color = red
        canvas.drawText("DOWN K0.25", left+160f, height-9f, paint)
        paint.color = dim
        canvas.drawText(String.format(Locale.US, "High $%,.0f   Low $%,.0f", high, low),
            max(left, right-275f), height-34f, paint)
    }
}
