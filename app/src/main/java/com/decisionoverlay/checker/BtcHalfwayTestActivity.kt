package com.decisionoverlay.checker

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.*
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

class BtcHalfwayTestActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val update = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1500L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 22)
            setBackgroundColor(Color.rgb(17, 22, 30))
        }
        fun txt(s: String, size: Float): TextView = TextView(this).apply {
            text = s; textSize = size; setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10); setTextIsSelectable(true)
        }
        layout.addView(txt("BTC — SECOND-HALF FUNNEL BACKTEST", 22f))
        layout.addView(txt("Scan minutes 7 through 14 elapsed (8 to 1 minute left). " +
            "At every minute, your funnel boundary is 0.05% x minutes remaining " +
            "above or below the start target. Find 100 near-line cases on EACH side " +
            "at EACH minute (+/-0.010 percentage points around the line). " +
            "Count how often price never touches the target again, and how often " +
            "it finishes on the same side. Binance.US 1-minute candles, 10:00–23:00 " +
            "Eastern, background scanning. This tests a hypothesis, not Kalshi odds.", 13f))
        start = Button(this).apply {
            text = "START BTC FUNNEL BACKTEST — BACKGROUND"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@BtcHalfwayTestActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(this@BtcHalfwayTestActivity,
                    Intent(this@BtcHalfwayTestActivity, BtcHalfwayScanService::class.java)
                        .setAction(BtcHalfwayScanService.START))
                refresh()
            }
        }
        stop = Button(this).apply {
            text = "STOP SCAN"
            setOnClickListener {
                startService(Intent(this@BtcHalfwayTestActivity,
                    BtcHalfwayScanService::class.java).setAction(BtcHalfwayScanService.STOP))
            }
        }
        layout.addView(start); layout.addView(stop)
        layout.addView(Button(this).apply {
            text = "LIVE BTC FUNNEL — 60 / 70 / 80 + KALSHI"
            setOnClickListener {
                startActivity(Intent(this@BtcHalfwayTestActivity,
                    BtcFunnelLiveActivity::class.java))
            }
        })
        layout.addView(Button(this).apply {
            text = "COPY RESULTS"
            setOnClickListener {
                val data = getSharedPreferences(BtcHalfwayScanService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (data.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("BTC funnel study results", data))
            }
        })
        status = txt("", 14f); results = txt("", 14f)
        layout.addView(status); layout.addView(results)
        setContentView(ScrollView(this).apply { addView(layout) })
        refresh()
    }
    override fun onResume() {
        super.onResume(); handler.removeCallbacks(update); handler.post(update)
    }
    override fun onPause() { handler.removeCallbacks(update); super.onPause() }
    private fun refresh() {
        if (!::status.isInitialized) return
        val p = getSharedPreferences(BtcHalfwayScanService.PREFS, MODE_PRIVATE)
        val phase = p.getString("phase", "idle")
        status.text = p.getString("status", "Press START to test historical Bitcoin prices.")
        results.text = p.getString("report", "")
        start.isEnabled = phase != "running" && phase != "stopping"
        stop.isEnabled = phase == "running" || phase == "stopping"
    }
}

object BtcHalfwayMath {
    const val GAP = 0.375
    const val TOLERANCE = 0.025
    const val GOAL = 100
    fun gap(price: Double, target: Double) = (price - target) / target * 100.0
    fun matches(price: Double, target: Double, above: Boolean): Boolean {
        if (price <= 0.0 || target <= 0.0) return false
        val d = gap(price, target)
        return (if (above) d > 0.0 else d < 0.0) &&
            abs(abs(d) - GAP) <= TOLERANCE + 1e-9
    }
    fun result(end: Double, target: Double, above: Boolean): String =
        if (end == target) "TIE"
        else if ((end > target) == above) "STAY"
        else "CROSS"
}

/** At 12 minutes elapsed the minute-11 close is the last known price. */
object BtcThreeMinuteMath {
    const val GAP = 0.10
    const val TOLERANCE = 0.01
    const val GOAL = 100
    const val ELAPSED = 12

    fun gap(price: Double, target: Double): Double =
        (price - target) / target * 100.0

    fun matches(price: Double, target: Double, above: Boolean): Boolean {
        if (!price.isFinite() || !target.isFinite() || price <= 0.0 || target <= 0.0)
            return false
        val delta = gap(price, target)
        return (if (above) delta > 0.0 else delta < 0.0) &&
            abs(abs(delta) - GAP) <= TOLERANCE + 1e-9
    }

    fun result(end: Double, target: Double, above: Boolean): String =
        if (end == target) "TIE"
        else if ((end > target) == above) "STAY"
        else "CROSS"
}

/** Hypothesis only, not a statistical proof of a 90% outcome. */
object BtcFunnelMath {
    const val RATE = 0.05 // percentage points per remaining minute
    const val TOLERANCE = 0.01 // percentage points on each side of line
    const val START = 7
    const val END = 14
    const val GOAL = 100
    fun threshold(elapsed: Int): Double {
        require(elapsed in START..END)
        return RATE * (15 - elapsed)
    }
    fun gap(price: Double, target: Double) = (price - target) / target * 100.0
    fun matches(price: Double, target: Double, elapsed: Int, above: Boolean): Boolean {
        if (!price.isFinite() || !target.isFinite() || price <= 0 || target <= 0) return false
        val g = gap(price, target)
        return (if (above) g > 0 else g < 0) &&
            abs(abs(g) - threshold(elapsed)) <= TOLERANCE + 1e-9
    }
    fun result(end: Double, target: Double, above: Boolean) =
        if (end == target) "TIE"
        else if ((end > target) == above) "STAY" else "CROSS"
}

class BtcHalfwayScanService : Service() {
    companion object {
        const val PREFS = "btc_funnel_second_half_v1"
        const val START = "btc.halfway.START"
        const val STOP = "btc.halfway.STOP"
        private const val CHANNEL = "btc_funnel_second_half"
        private const val RESULT_CHANNEL = "btc_funnel_second_half_results"
        private const val NOTIFICATION_ID = 7195
    }
    private val cancel = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val zone = ZoneId.of("America/New_York")
    private val minute = 60000L
    private val quarter = 15 * minute
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS).callTimeout(40, TimeUnit.SECONDS).build()
    private data class Candle(val high: Double, val low: Double, val close: Double)
    private data class Cases(val elapsed: Int, val above: Boolean,
        var stay: Int = 0, var cross: Int = 0, var tie: Int = 0,
        var touch: Int = 0, var least: Double = Double.POSITIVE_INFINITY,
        var greatest: Double = 0.0) {
        val n: Int get() = stay + cross + tie
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "BTC funnel scan",
                NotificationManager.IMPORTANCE_LOW))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("btc_halfway_results",
                "BTC scan results", NotificationManager.IMPORTANCE_HIGH))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(RESULT_CHANNEL,
                "BTC funnel scan finished or failed", NotificationManager.IMPORTANCE_HIGH))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            cancel.set(true)
            if (running.get()) save("stopping", "Stopping after current request.")
            else { save("stopped", "Scan is not running."); stopSelf() }
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_STICKY
        cancel.set(false)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(NOTIFICATION_ID, notification("Scanning BTC history"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(NOTIFICATION_ID, notification("Scanning BTC history"))
        save("running", "Scanning BTC in the background; you can close the app.", "")
        Thread {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "decisionoverlay:btcHistory")
            try {
                lock.acquire(3L * 60 * 60 * 1000)
                val result = scan()
                if (cancel.get()) save("stopped", "Stopped; partial results saved.", result)
                else {
                    save("done", "Finished Bitcoin historical scan.", result)
                    resultNotification("BTC scan FINISHED", "Open the app to read your results.")
                }
            } catch (e: Exception) {
                val previous = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                val reason = "Binance request failed: " + (e.message ?: "unknown")
                save("error", reason, previous)
                if (!cancel.get()) resultNotification("BTC scan FAILED", reason.take(115))
            } finally {
                if (lock.isHeld) lock.release()
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }
    private fun notification(info: String): Notification =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC funnel second-half backtest")
            .setContentText(info).setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(PendingIntent.getActivity(this, 7193,
                Intent(this, BtcHalfwayTestActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)).build()
    private fun resultNotification(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(PendingIntent.getActivity(this, 7194,
                Intent(this, BtcHalfwayTestActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID + 1, notification)
    }

    private fun finishedNotice(title: String, message: String) {
        val notification = NotificationCompat.Builder(this, "btc_halfway_results")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, 7194,
                Intent(this, BtcHalfwayTestActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(7194, notification)
    }
    private fun save(phase: String, status: String, report: String? = null) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", phase).putString("status", status)
        if (report != null) editor.putString("report", report)
        editor.apply()
    }
    private fun dayCandles(start: Long, end: Long): Map<Long, Candle> {
        val url = "https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
            "&interval=1m&startTime=" + start + "&endTime=" + end + "&limit=1000"
        for (tryNumber in 1..3) {
            if (cancel.get()) return emptyMap()
            try {
                val text = http.newCall(Request.Builder().url(url).get().build())
                    .execute().use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful)
                            error("Binance.US HTTP " + it.code + ": " + body.take(100))
                        body
                    }
                val data = JSONArray(text)
                val result = HashMap<Long, Candle>()
                for (i in 0 until data.length()) {
                    val row = data.getJSONArray(i)
                    result[row.getLong(0)] = Candle(row.getDouble(2),
                        row.getDouble(3), row.getDouble(4))
                }
                return result
            } catch (e: Exception) {
                if (tryNumber == 3) throw e
                Thread.sleep(tryNumber * 1500L)
            }
        }
        return emptyMap()
    }
    private fun scan(): String {
        // 8 observation times x 2 directions. Markets may occur in multiple buckets.
        val groups = (BtcFunnelMath.START..BtcFunnelMath.END)
            .flatMap { elapsed -> listOf(Cases(elapsed, true), Cases(elapsed, false)) }
        val latestEnd = System.currentTimeMillis() / quarter * quarter
        var date = LocalDate.now(zone)
        var days = 0
        var markets = 0
        var skipped = 0
        var noDataDays = 0
        while (days < 2500 && !cancel.get() &&
            groups.any { it.n < BtcFunnelMath.GOAL }) {
            val from = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            val until = min(date.atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
                latestEnd)
            date = date.minusDays(1)
            if (until <= from) continue
            // At most 781 1-minute candles per request; Binance.US limit is 1000.
            val candles = dayCandles(from - minute, until - 1)
            days++
            if (candles.isEmpty()) {
                noDataDays++
                if (noDataDays >= 60) break
                continue
            }
            noDataDays = 0
            var marketStart = from
            while (marketStart + quarter <= until && !cancel.get()) {
                val target = candles[marketStart - minute]?.close
                val end = candles[marketStart + 14 * minute]?.close
                if (target == null || end == null || target <= 0.0 ||
                    (0..14).any { candles[marketStart + it * minute] == null }) {
                    skipped++; marketStart += quarter; continue
                }
                markets++
                for (g in groups) {
                    if (g.n >= BtcFunnelMath.GOAL) continue
                    // Minute N observes the close of the immediately preceding
                    // fully completed 1-minute candle (no future data leakage).
                    val price = candles[marketStart + (g.elapsed - 1) * minute]!!.close
                    if (!BtcFunnelMath.matches(price, target, g.elapsed, g.above)) continue
                    val distance = abs(BtcFunnelMath.gap(price, target))
                    g.least = min(g.least, distance)
                    g.greatest = max(g.greatest, distance)
                    when (BtcFunnelMath.result(end, target, g.above)) {
                        "STAY" -> g.stay++
                        "CROSS" -> g.cross++
                        else -> g.tie++
                    }
                    // Even a temporary wick touch counts as touching the target.
                    // That is distinct from a finish on the opposite side.
                    val touched = (g.elapsed..14).any { offset ->
                        val bar = candles[marketStart + offset * minute]!!
                        if (g.above) bar.low <= target else bar.high >= target
                    }
                    if (touched) g.touch++
                }
                marketStart += quarter
            }
            if (days == 1 || days % 5 == 0 ||
                groups.all { it.n >= BtcFunnelMath.GOAL }) {
                val complete = groups.count { it.n >= BtcFunnelMath.GOAL }
                val progress = complete.toString() + "/16 groups complete; lowest " +
                    groups.minOf { it.n }.toString() + "/100"
                save("running", "Days " + days + "; markets " + markets + ". " + progress,
                    resultText(groups, days, markets, skipped))
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("Days " + days + "; " + progress))
            }
            if (!cancel.get()) Thread.sleep(250L)
        }
        val ending = if (groups.all { it.n >= BtcFunnelMath.GOAL })
            "\nAll 16 groups reached 100 matches."
        else "\nSome groups did not reach 100; see actual sample sizes."
        return resultText(groups, days, markets, skipped) + ending
    }

    private fun resultText(groups: List<Cases>, days: Int, markets: Int,
        skipped: Int): String = buildString {
        appendLine("BTC — SECOND-HALF FUNNEL 90% HYPOTHESIS BACKTEST")
        appendLine("Binance.US BTCUSDT 1-minute data; 10:00–23:00 America/New_York, DST-aware.")
        appendLine("15-minute markets start at :00/:15/:30/:45.")
        appendLine("Target = prior 1-minute candle close; end = final 1-minute candle close.")
        appendLine("Observation at minute N = last COMPLETED candle (minute N-1) close.")
        appendLine("Threshold = 0.05% x minutes remaining; UP and DOWN separate.")
        appendLine("Near-line matches: +/-0.010 percentage points around each threshold.")
        appendLine("NO TOUCH means no later candle WICK touches or crosses the target.")
        appendLine("STAY means final close remains on observed side, even if touched.")
        appendLine("CROSS = opposite final side. Ending TIE separately counted.")
        appendLine("A 90% result is a historical fraction, NOT a validated winning chance.")
        appendLine("One market can appear in multiple minute buckets; results overlap.")
        appendLine("Binance.US prices are NOT Kalshi's official settlement index.")
        appendLine("Days scanned " + days + "; markets scanned " + markets +
            "; markets missing candles " + skipped)
        appendLine()
        for (g in groups) {
            appendLine(String.format(Locale.US,
                "%d MINUTES ELAPSED (%d LEFT), %s %.3f%%",
                g.elapsed, 15 - g.elapsed, if (g.above) "ABOVE +" else "BELOW -",
                BtcFunnelMath.threshold(g.elapsed)))
            appendLine("Found " + g.n + " / 100")
            if (g.n == 0) { appendLine("No matches yet.\n"); continue }
            appendLine(String.format(Locale.US, "Actual gap range %.4f%%–%.4f%%",
                g.least, g.greatest))
            appendLine(String.format(Locale.US,
                "NO TOUCH: %d/%d = %.1f%%", g.n-g.touch, g.n,
                100.0 * (g.n - g.touch) / g.n))
            appendLine(String.format(Locale.US,
                "STAY at finish: %d/%d = %.1f%%", g.stay, g.n, 100.0*g.stay/g.n))
            appendLine(String.format(Locale.US,
                "CROSS at finish: %d/%d = %.1f%%", g.cross, g.n, 100.0*g.cross/g.n))
            appendLine(String.format(Locale.US,
                "TIE at finish: %d/%d = %.1f%%", g.tie, g.n, 100.0*g.tie/g.n))
            appendLine(String.format(Locale.US,
                "Touched target after observation: %d/%d = %.1f%%",
                g.touch, g.n, 100.0*g.touch/g.n))
            appendLine("Strict 90% no-touch rule: " +
                (if (g.n < 100) "PENDING"
                 else if (g.n - g.touch >= 90) "MET in sample" else "NOT MET in sample"))
            appendLine()
        }
        appendLine("The second-half test cannot establish first-half performance.")
        appendLine("Historical proxy results are not trading advice or Kalshi odds.")
    }
}
