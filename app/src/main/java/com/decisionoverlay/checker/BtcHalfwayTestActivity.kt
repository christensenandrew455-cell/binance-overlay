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
        layout.addView(txt("BTC — THREE MINUTES LEFT / 0.10% TEST", 22f))
        layout.addView(txt("Test Bitcoin ONLY, exactly 3 minutes before each 15-minute " +
            "market ends (12 minutes elapsed). Find 100 DOWN and 100 UP historical " +
            "cases when BTC is 0.090%–0.110% away from target (around $66 at $65,000). " +
            "Report how many finish on that side, cross back, tie or touch the target. " +
            "10 AM–11 PM Eastern, DST-aware. Runs in background; Binance.US candles " +
            "are a proxy, not Kalshi settlement data or actual Kalshi odds.", 13f))
        start = Button(this).apply {
            text = "START 3-MIN BTC TEST — BACKGROUND"
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
            text = "COPY RESULTS"
            setOnClickListener {
                val data = getSharedPreferences(BtcHalfwayScanService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (data.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("BTC 3-minute study result", data))
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

class BtcHalfwayScanService : Service() {
    companion object {
        const val PREFS = "btc_three_minute_010_v1"
        const val START = "btc.halfway.START"
        const val STOP = "btc.halfway.STOP"
        private const val CHANNEL = "btc_three_min_010"
        private const val RESULT_CHANNEL = "btc_three_min_010_results"
        private const val NOTIFICATION_ID = 7193
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
            .createNotificationChannel(NotificationChannel(CHANNEL, "BTC three-minute scan",
                NotificationManager.IMPORTANCE_LOW))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("btc_halfway_results",
                "BTC scan results", NotificationManager.IMPORTANCE_HIGH))
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(RESULT_CHANNEL,
                "BTC three-minute scan finished or failed", NotificationManager.IMPORTANCE_HIGH))
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
            .setContentTitle("Bitcoin 3m / 0.10% historical test")
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
        val groups = listOf(Cases(BtcThreeMinuteMath.ELAPSED, true),
            Cases(BtcThreeMinuteMath.ELAPSED, false))
        val latestEnd = System.currentTimeMillis() / quarter * quarter
        var date = LocalDate.now(zone)
        var days = 0
        var markets = 0
        var skipped = 0
        var noDataDays = 0
        // One <=781-candle fetch per day. Stop at 100 cases per group or
        // after 2500 days / 60 consecutive dates without Binance history.
        while (days < 2500 && !cancel.get() &&
            groups.any { it.n < BtcThreeMinuteMath.GOAL }) {
            val from = date.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            val until = min(date.atTime(23, 0).atZone(zone).toInstant().toEpochMilli(),
                latestEnd)
            date = date.minusDays(1)
            if (until <= from) continue
            val candles = dayCandles(from - minute, until - 1)
            days++
            if (candles.isEmpty()) {
                noDataDays++
                if (noDataDays >= 60) break
                continue
            }
            noDataDays = 0
            var start = from
            while (start + quarter <= until && !cancel.get()) {
                val target = candles[start - minute]?.close
                val end = candles[start + 14 * minute]?.close
                if (target == null || end == null || target <= 0.0 ||
                    (0..14).any { candles[start + it * minute] == null }) {
                    skipped++; start += quarter; continue
                }
                markets++
                for (g in groups) {
                    if (g.n >= BtcThreeMinuteMath.GOAL) continue
                    val price = candles[start + (g.elapsed - 1) * minute]!!.close
                    if (!BtcThreeMinuteMath.matches(price, target, g.above)) continue
                    val d = abs(BtcThreeMinuteMath.gap(price, target))
                    g.least = min(g.least, d); g.greatest = max(g.greatest, d)
                    when (BtcThreeMinuteMath.result(end, target, g.above)) {
                        "STAY" -> g.stay++
                        "CROSS" -> g.cross++
                        else -> g.tie++
                    }
                    // A candle can touch the target and still settle on original side.
                    val touched = (g.elapsed..14).any { offset ->
                        val bar = candles[start + offset * minute]!!
                        if (g.above) bar.low <= target else bar.high >= target
                    }
                    if (touched) g.touch++
                }
                start += quarter
            }
            if (days == 1 || days % 5 == 0 ||
                groups.all { it.n >= BtcThreeMinuteMath.GOAL }) {
                val progress = groups.joinToString(" | ") {
                    "3m left " + (if (it.above) "UP" else "DOWN") +
                        " " + it.n + "/100"
                }
                save("running", "Days " + days + ", markets " + markets +
                    ". " + progress, resultText(groups, days, markets, skipped))
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notification("Days: " + days + ". " + progress))
            }
            if (!cancel.get()) Thread.sleep(250L)
        }
        val ending = if (groups.all { it.n >= BtcThreeMinuteMath.GOAL })
            "\nBoth 3-minute groups reached 100 matches."
        else "\nSome groups did not reach 100; actual sample sizes shown."
        return resultText(groups, days, markets, skipped) + ending
    }
    private fun resultText(groups: List<Cases>, days: Int, markets: Int,
        skipped: Int): String = buildString {
        appendLine("BTC — 3 MINUTES LEFT / 0.10% BACKTEST")
        appendLine("Binance.US BTCUSDT 1-minute data; ONLY 10:00–23:00 " +
            "America/New_York (DST-aware).")
        appendLine("Market starts at :00/:15/:30/:45. All observations use " +
            "completed 1-minute candles.")
        appendLine("Target = last 1-minute close before start; end = last minute " +
            "close before the market settles.")
        appendLine("Observation: 12 minutes elapsed / 3 minutes remaining; last COMPLETED candle.")
        appendLine("Match range: 0.090%–0.110% from target, UP and DOWN separately.")
        appendLine("STAY = ends on observed side; CROSS = finishes on opposite side.")
        appendLine("Exact ending ties are separate. TOUCH may happen even if price ends " +
            "on the original side.")
        appendLine("Binance.US data is NOT the official Kalshi settlement index.")
        appendLine("Days scanned " + days + "; markets scanned " + markets +
            "; markets missing candles " + skipped)
        appendLine()
        for (g in groups) {
            appendLine(g.elapsed.toString() + " minutes INTO market (" +
                (15 - g.elapsed) + " left), " +
                (if (g.above) "ABOVE +0.10%" else "BELOW -0.10%"))
            appendLine("Found " + g.n + " / 100")
            if (g.n == 0) { appendLine("No matches yet.\n"); continue }
            appendLine(String.format(Locale.US, "Actual gap range: %.4f%%–%.4f%%",
                g.least, g.greatest))
            appendLine(String.format(Locale.US, "Stayed on observed side: %d/%d = %.1f%%",
                g.stay, g.n, 100.0 * g.stay / g.n))
            appendLine(String.format(Locale.US, "Crossed target by finish: %d/%d = %.1f%%",
                g.cross, g.n, 100.0 * g.cross / g.n))
            appendLine(String.format(Locale.US, "Tied at finish: %d/%d = %.1f%%",
                g.tie, g.n, 100.0 * g.tie / g.n))
            appendLine(String.format(Locale.US, "Touched target after observation: %d/%d = %.1f%%",
                g.touch, g.n, 100.0 * g.touch / g.n))
            appendLine()
        }
        appendLine("This is a Binance.US candle backtest, NOT Kalshi CF index settlement.")
        appendLine("Historical stay rates are not validated probabilities or trading advice.")
    }
}
