package com.decisionoverlay.checker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Historical, Binance.US-only 100-market replay.
 * Finds the FIRST hit per market per whole-number percentage; never counts a
 * second crossing. No live alerts, no trades, no claimed historical Kalshi asks.
 */
class BtcRangeBacktestService : Service() {
    companion object {
        const val PREFS = "btc_range_backtest_v1"
        const val START = "btc.range.START"
        const val STOP = "btc.range.STOP"
        private const val CHANNEL = "btc_range_scanner"
        private const val RESULT_CHANNEL = "btc_range_results"
        private const val RUN_ID = 4812
        private const val DONE_ID = 4813
        private const val MINUTE = BtcRangeMath.MINUTE_MS
    }

    private data class Count(var firstHits: Int = 0, var qualifies: Int = 0)
    private data class Candidate(val level: Int, val from: Int, val to: Int,
        val qualifyingHits: Int, val allHits: Int)

    private val ny = ZoneId.of("America/New_York")
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL, "BTC backtest progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            RESULT_CHANNEL, "BTC backtest finished", NotificationManager.IMPORTANCE_DEFAULT))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            cancelled.set(true)
            if (running.get()) status("Stopping scan after current request.")
            else { status("Stopped."); stopSelf() }
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        cancelled.set(false)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(RUN_ID, notification("Checking BTC history"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(RUN_ID, notification("Checking BTC history"))
        persist("running", "Preparing 100 completed BTC markets.", "")
        Thread {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "decisionoverlay:rangeScan")
            try {
                lock.acquire(45L * 60L * 1000L)
                val report = scan()
                if (cancelled.get()) persist("stopped", "Stopped.", report)
                else {
                    persist("done", "100-market check complete.", report)
                    doneNotification("BTC range check completed")
                }
            } catch (e: Exception) {
                val message = (e.message ?: e.javaClass.simpleName).take(220)
                persist(if (cancelled.get()) "stopped" else "error",
                    "Backtest: " + message, "")
            } finally {
                if (lock.isHeld) lock.release()
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun persist(state: String, message: String, report: String? = null) {
        val ed = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", state).putString("status", message)
        if (report != null) ed.putString("report", report)
        ed.apply()
    }
    private fun status(message: String) {
        persist("running", message)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RUN_ID, notification(message))
    }
    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, RUN_ID,
            Intent(this, BtcRangeBacktestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("BTC 100-market backtest")
            .setContentText(message).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).build()
    }
    private fun doneNotification(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, DONE_ID,
            Intent(this, BtcRangeBacktestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(DONE_ID, NotificationCompat.Builder(this, RESULT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(message)
                .setContentText("Open app for percentage and minute range")
                .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun download(day: LocalDate): Map<Long, BtcAcmMath.Candle> {
        // One 840-candle request per day: 09:00 warmup through 23:00 NY.
        val start = day.atTime(9, 0).atZone(ny).toInstant().toEpochMilli()
        val end = day.atTime(23, 0).atZone(ny).toInstant().toEpochMilli() - 1L
        val url = "https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
            "&interval=1m&startTime=" + start + "&endTime=" + end + "&limit=1000"
        var failure: Exception? = null
        for (attempt in 1..3) {
            if (cancelled.get()) return emptyMap()
            try {
                val rows = http.newCall(Request.Builder().url(url).get().build())
                    .execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) error(
                            "Binance.US HTTP " + response.code + ": " + body.take(100))
                        JSONArray(body)
                    }
                val candles = HashMap<Long, BtcAcmMath.Candle>()
                for (i in 0 until rows.length()) {
                    val c = rows.getJSONArray(i)
                    val candle = BtcAcmMath.Candle(
                        c.getLong(0), c.getDouble(1), c.getDouble(2),
                        c.getDouble(3), c.getDouble(4))
                    if (candle.valid()) candles[candle.at] = candle
                }
                return candles
            } catch (e: Exception) {
                failure = e
                if (attempt < 3 && !cancelled.get()) Thread.sleep(attempt * 1000L)
            }
        }
        throw failure ?: IllegalStateException("Binance candles unavailable")
    }

    private fun scan(): String {
        val counts = Array(91) { Array(15) { Count() } }
        var markets = 0
        var days = 0
        var missing = 0
        var day = LocalDate.now(ny).minusDays(1)
        while (markets < BtcRangeMath.TARGET_MARKETS && days < 14 && !cancelled.get()) {
            val candles = download(day)
            val first = day.atTime(10, 0).atZone(ny).toInstant().toEpochMilli()
            for (quarter in 0 until 52) {
                if (cancelled.get() || markets >= BtcRangeMath.TARGET_MARKETS) break
                val start = first + quarter * BtcRangeMath.MARKET_MS
                val target = candles[start - MINUTE]?.close
                if (target == null || target <= 0.0 ||
                    (0..14).any { candles[start + it * MINUTE] == null }) {
                    missing++
                    continue
                }
                // All 15 observations must be complete; no future data used.
                val observations = (14 downTo 1).map { left ->
                    val at = start + (14L - left) * MINUTE
                    val spot = candles.getValue(at).close
                    BtcRangeMath.snapshot(candles, at, spot, target, left)
                }
                if (observations.any { it == null }) { missing++; continue }
                markets++
                var previousMax = 50
                for (observation in observations) {
                    val snap = observation!!
                    for (level in BtcRangeMath.firstCrossed(previousMax, snap.percentage)) {
                        val count = counts[level][snap.remaining]
                        count.firstHits++
                        if (snap.qualifies) count.qualifies++
                    }
                    previousMax = maxOf(previousMax, snap.percentage.toInt().coerceAtMost(90))
                }
                if (markets % 10 == 0) status(
                    "Checked " + markets + "/100 markets, " + days + " full days completed")
            }
            days++
            day = day.minusDays(1)
        }
        if (cancelled.get()) return "Stopped after $markets markets."
        if (markets < BtcRangeMath.TARGET_MARKETS) {
            throw IllegalStateException(
                "Only $markets/100 complete markets in 14 days ($missing missing).")
        }

        // For each level, find its BEST continuous time interval, not disjoint minutes.
        // Ties prefer more total first hits and then the longer contiguous interval.
        var best: Candidate? = null
        for (level in BtcRangeMath.MIN_LEVEL..BtcRangeMath.MAX_LEVEL) {
            for (startLeft in 14 downTo 1) {
                var sumQualified = 0
                var sumHits = 0
                for (endLeft in startLeft downTo 1) {
                    val current = counts[level][endLeft]
                    if (current.qualifies == 0) break
                    sumQualified += current.qualifies
                    sumHits += current.firstHits
                    val choice = Candidate(level, startLeft, endLeft,
                        sumQualified, sumHits)
                    val incumbent = best
                    if (incumbent == null ||
                        choice.qualifyingHits > incumbent.qualifyingHits ||
                        (choice.qualifyingHits == incumbent.qualifyingHits &&
                            choice.allHits > incumbent.allHits) ||
                        (choice.qualifyingHits == incumbent.qualifyingHits &&
                            choice.allHits == incumbent.allHits &&
                            choice.from - choice.to > incumbent.from - incumbent.to))
                        best = choice
                }
            }
        }
        if (best == null) return "No qualifying percentage/minute range found in 100 markets.\n" +
            "Simulated Kalshi asks only; actual historical Kalshi quotes were unavailable."

        return String.format(Locale.US,
            "PERCENTAGE: %d%%\nMINUTE RANGE: %d to %d minutes remaining\n\n" +
            "100 complete BTC markets, 10 AM–11 PM New York. " +
            "First hit only per market and percentage.\n" +
            "Kalshi comparison uses synthetic volatility-based asks (60–79c) " +
            "and an illustrative taker-fee allowance. " +
            "NOT actual historical Kalshi prices or verified profitability.",
            best.level, best.from, best.to)
    }
}
