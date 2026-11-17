package com.decisionoverlay.checker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min

/**
 * BTC-specific funnel view. The live service runs independently of this screen.
 * It NEVER places orders; alerts only when an empirical 60/70/80 line has
 * validated AND the live Kalshi ask clears conservative historical evidence.
 */
class BtcFunnelLiveActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private lateinit var report: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val handler = Handler(Looper.getMainLooper())
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val updater = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 22)
            setBackgroundColor(Color.rgb(16, 21, 29))
        }
        fun label(content: String, size: Float) = TextView(this).apply {
            text = content
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
            setTextIsSelectable(true)
        }
        panel.addView(label("BTC — LIVE 50 / 60 / 70 / 80 / 95 FUNNEL", 21f))
        panel.addView(label(
            "Target = reference 50% line. The 0.05% per minute remaining outer " +
            "boundary on BOTH sides is your proposed 95% ceiling, NOT a " +
            "verified 95% win chance. No alerts beyond it. " +
            "60%, 70%, and 80% inner levels are independently backtested " +
            "using 10:00–23:00 New York BTC markets. Only validated " +
            "levels with a favorable Kalshi ask after estimated fees and " +
            "sampling uncertainty can alert. Never places trades.", 13f))
        start = Button(this).apply {
            text = "BUILD LINES + START BACKGROUND MONITOR"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@BtcFunnelLiveActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(this@BtcFunnelLiveActivity,
                    Intent(this@BtcFunnelLiveActivity, BtcFunnelLiveService::class.java)
                        .setAction(BtcFunnelLiveService.START))
                refresh()
            }
        }
        stop = Button(this).apply {
            text = "STOP MONITORING"
            setOnClickListener {
                startService(Intent(this@BtcFunnelLiveActivity,
                    BtcFunnelLiveService::class.java).setAction(BtcFunnelLiveService.STOP))
            }
        }
        panel.addView(start)
        panel.addView(stop)
        panel.addView(Button(this).apply {
            text = "COPY FUNNEL LEVELS AND COMPARISON"
            setOnClickListener {
                val copy = getSharedPreferences(BtcFunnelLiveService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (copy.isNotEmpty()) (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("BTC live funnel", copy))
            }
        })
        status = label("Press START to calibrate levels and monitor BTC.", 14f)
        report = label("", 13f)
        panel.addView(status)
        panel.addView(report)
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
        val prefs = getSharedPreferences(BtcFunnelLiveService.PREFS, MODE_PRIVATE)
        val phase = prefs.getString("phase", "idle")
        status.text = prefs.getString("status", "Press START to calibrate.")
        report.text = prefs.getString("report", "")
        start.isEnabled = phase != "running" && phase != "starting"
        stop.isEnabled = phase == "running" || phase == "starting"
    }
}

class BtcFunnelLiveService : Service() {
    companion object {
        const val PREFS = "btc_funnel_live_v1"
        const val START = "btc.funnel.live.START"
        const val STOP = "btc.funnel.live.STOP"
        private const val CHANNEL = "btc_funnel_monitor"
        private const val ALERTS = "btc_funnel_levels"
        private const val SERVICE_ID = 8357
    }
    private val running = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val alerted = HashSet<String>()
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS).build()

    private data class Market(
        val ticker: String,
        val target: Double,
        val end: Long,
        val yesAsk: Double?,
        val noAsk: Double?
    )

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL,
            "BTC funnel background status", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(ALERTS,
            "BTC 60 70 80 line alerts", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            canceled.set(true)
            save("stopping", "Stopping BTC monitor.")
            if (!running.get()) { save("stopped", "BTC monitor stopped."); stopSelf() }
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_STICKY
        canceled.set(false)
        alerted.clear()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(SERVICE_ID, foreground("Preparing historical BTC lines"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else startForeground(SERVICE_ID, foreground("Preparing historical BTC lines"))
        save("starting", "Building 60%, 70%, 80% levels from older/newer BTC data.")
        Thread {
            try {
                val history = BtcFunnelHistory.load(this,
                    { progress -> save("starting", progress) }, { canceled.get() })
                save("running", "BTC lines calibrated. Monitoring live quotes.")
                while (!canceled.get()) {
                    try {
                        val snapshot = check(history)
                        if (!canceled.get())
                            save("running", "Live BTC funnel updated. Next check in ~15 seconds.",
                                snapshot)
                    } catch (e: Exception) {
                        if (!canceled.get()) save("running",
                            "Live data unavailable; no alerts. " +
                                (e.message ?: e.javaClass.simpleName).take(120))
                    }
                    if (!canceled.get()) Thread.sleep(15_000L)
                }
            } catch (e: Exception) {
                if (!canceled.get())
                    save("error", "BTC calibration failed: " +
                        (e.message ?: e.javaClass.simpleName))
            } finally {
                save(if (canceled.get()) "stopped"
                    else getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("phase", "error").orEmpty(),
                    if (canceled.get()) "BTC funnel monitor stopped."
                    else getSharedPreferences(PREFS, MODE_PRIVATE)
                        .getString("status", "Stopped.").orEmpty())
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun save(phase: String, state: String, report: String? = null) {
        val p = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", phase).putString("status", state)
        if (report != null) p.putString("report", report)
        p.apply()
    }

    private fun foreground(state: String): Notification {
        val open = PendingIntent.getActivity(this, SERVICE_ID,
            Intent(this, BtcFunnelLiveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC funnel monitor")
            .setContentText(state)
            .setContentIntent(open)
            .setOnlyAlertOnce(true).setOngoing(true).build()
    }

    private fun alert(title: String, msg: String, id: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, SERVICE_ID,
            Intent(this, BtcFunnelLiveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val item = NotificationCompat.Builder(this, ALERTS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(msg)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true).setContentIntent(open).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(id, item)
    }

    private fun check(history: HoldProbabilityEngine.Historical): String {
        val market = liveMarket()
        val now = System.currentTimeMillis()
        val left = ((market.end - now) / 60_000.0)
        require(left > 0.0 && left <= 15.0) { "No eligible 15-minute BTC market" }
        val minute = ceil(left).toInt().coerceIn(1, 15)
        val spot = JSONObject(fetch(
            "https://api.binance.us/api/v3/ticker/price?symbol=BTCUSDT"))
            .getString("price").toDouble()
        require(spot.isFinite() && spot > 0) { "Invalid Binance.US BTC price" }
        val side = when { spot > market.target -> 1; spot < market.target -> -1; else -> 0 }
        val distance = BtcFunnelLevels.percentageGap(spot, market.target)
        val ceiling = BtcFunnelLevels.ceilingPct(minute)
        val beyond = !BtcFunnelLevels.withinCeiling(distance, minute)
        val ask = if (side == 1) market.yesAsk
            else if (side == -1) market.noAsk else null
        val currentLevels = if (side == 0) emptyList()
            else BtcFunnelLevels.goals.mapNotNull {
                BtcFunnelLevels.findLevel(history, minute, side, it)
            }
        val best = currentLevels.filter {
            it.validated && distance >= it.gapPct && it.gapPct > 0.0
        }.maxByOrNull { it.goal }
        val comparison = if (best != null)
            BtcFunnelLevels.comparison(best, ask) else "NO VALIDATED INNER LEVEL REACHED"
        val acceptable = !beyond && best != null &&
            comparison == "BELOW CONSERVATIVE HISTORICAL BOUND"

        if (acceptable && best != null) {
            val identity = market.ticker + ":" + side + ":" + best.goal
            if (alerted.add(identity)) {
                val note = String.format(Locale.US,
                    "BTC $%.2f; %.1fm left; %s ask %.1fc; model held-out %.1f%%",
                    spot, left, if (side == 1) "YES" else "NO",
                    (ask ?: 0.0) * 100.0, best.observedPct)
                alert("BTC " + best.goal + "% funnel level reached", note,
                    8400 + best.goal)
            }
        }

        return buildString {
            appendLine("BTC — LIVE FUNNEL | 10:00–23:00 America/New_York")
            appendLine("Market: " + market.ticker + " | " +
                String.format(Locale.US, "%.2f minutes left (bucket %d)", left, minute))
            appendLine(String.format(Locale.US,
                "Kalshi target $%.2f | Binance.US live spot $%.2f | signed gap %+.4f%%",
                market.target, spot, (spot - market.target) / market.target * 100))
            appendLine("TARGET = DISPLAY 50% REFERENCE (not an empirical win rate).")
            appendLine(String.format(Locale.US,
                "OUTER USER 95%% REFERENCE: +/-%.3f%% | YES $%.2f | NO $%.2f",
                ceiling, BtcFunnelLevels.priceAt(market.target, ceiling, 1),
                BtcFunnelLevels.priceAt(market.target, ceiling, -1)))
            appendLine("The 95% reference is NOT validated; the first half was not in the earlier 90% test.")
            appendLine("Outer boundary crossed: " + beyond +
                if (beyond) " — NO TRADE / NO ALERT" else "")
            appendLine(String.format(Locale.US,
                "Kalshi BUY asks: YES %s | NO %s | relevant %s",
                askText(market.yesAsk), askText(market.noAsk), askText(ask)))
            if (ask != null) appendLine(String.format(Locale.US,
                "Relevant ask + estimated fee: %.2f%% break-even",
                HoldProbabilityEngine.breakEven(ask) * 100.0))
            appendLine("Best level reached: " +
                (if (best == null) "NONE VALIDATED" else best.goal.toString() + "%"))
            appendLine("Comparison: " + comparison)
            appendLine(if (acceptable) "ALERT ELIGIBLE (once per market/side/level)."
                else "NO ALERT — model line/price comparison did not qualify.")
            appendLine()
            appendLine("CURRENT MINUTE LEVELS (older calibration, newer held-out):")
            for (direction in listOf(1, -1)) {
                appendLine(if (direction == 1) "ABOVE / YES" else "BELOW / NO")
                for (goal in BtcFunnelLevels.goals) {
                    val line = BtcFunnelLevels.findLevel(history, minute, direction, goal)
                    appendLine(goal.toString() + "%: " +
                        BtcFunnelLevels.lineText(line, market.target, direction))
                }
            }
            appendLine()
            appendLine("ALL 15 MINUTE MARKS; reference +/- boundary and validated inner lines:")
            appendLine("LEFT | SIDE | OUTER 95% REF | 60% | 70% | 80%")
            for (m in 15 downTo 1) {
                for (direction in listOf(1, -1)) {
                    fun mark(goal: Int): String {
                        val l = BtcFunnelLevels.findLevel(history, m, direction, goal)
                        return if (l == null || !l.validated) "—"
                        else String.format(Locale.US, "$%.2f", BtcFunnelLevels.priceAt(
                            market.target, l.gapPct, direction))
                    }
                    appendLine(String.format(Locale.US,
                        "%2d | %s | $%.2f | %s | %s | %s",
                        m, if (direction == 1) "UP  " else "DOWN",
                        BtcFunnelLevels.priceAt(market.target,
                            BtcFunnelLevels.ceilingPct(m), direction),
                        mark(60), mark(70), mark(80)))
                }
            }
            appendLine()
            appendLine("Calibration: " + history.trainMarkets +
                " older and " + history.testMarkets +
                " newer markets, 0.01 percentage-point buckets.")
            appendLine("Each reported inner line requires 100+ samples in EACH period and")
            appendLine("held-out rate meeting its label. Many levels may remain unestablished.")
            appendLine("Historical marks use COMPLETED 1m candles; live spot is intraminute.")
            appendLine("Binance.US proxy is NOT Kalshi's CF settlement index.")
            appendLine("Kalshi asks are current quotes, NOT objective probabilities; no orders placed.")
        }
    }

    private fun liveMarket(): Market {
        val json = JSONObject(fetch(
            "https://external-api.kalshi.com/trade-api/v2/markets" +
                "?series_ticker=KXBTC15M&status=open&limit=100"))
        val markets = json.optJSONArray("markets") ?: error("No Kalshi BTC15M markets")
        val now = System.currentTimeMillis()
        return (0 until markets.length()).mapNotNull { i ->
            val v = markets.getJSONObject(i)
            val end = runCatching {
                Instant.parse(v.getString("close_time")).toEpochMilli()
            }.getOrNull() ?: return@mapNotNull null
            val target = v.optString("floor_strike").toDoubleOrNull() ?: return@mapNotNull null
            if (end <= now || end > now + 15L * 60_000L ||
                target <= 0 || !TradingHours.allowedMarketStart(end - 15L * 60_000L))
                return@mapNotNull null
            Market(v.optString("ticker"), target, end,
                validAsk(v.optString("yes_ask_dollars")),
                validAsk(v.optString("no_ask_dollars")))
        }.minByOrNull { it.end } ?: error(
            "No active BTC 15-minute contract within 10 AM–11 PM New York")
    }

    private fun validAsk(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
    private fun askText(value: Double?): String =
        if (value == null) "UNAVAILABLE" else String.format(Locale.US, "%.2fc", value * 100)

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).get().build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("HTTP " + it.code + ": " + body.take(100))
            require(body.isNotBlank()) { "Empty API reply" }
            body
        }
}
