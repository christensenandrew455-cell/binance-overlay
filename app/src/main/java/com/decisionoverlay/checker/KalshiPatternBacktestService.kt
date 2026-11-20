package com.decisionoverlay.checker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Historical Binance.US BTCUSDT collector, launched ONLY by the BACKTEST
 * button. Legacy class name is preserved for Android manifest compatibility.
 * There are NO Kalshi requests or automatic GitHub validation runs.
 */
class KalshiPatternBacktestService : Service() {
    companion object {
        const val PREFS = "binance_27_pattern_scan_v2"
        const val STATUS = "status"
        const val REPORT = "report"
        const val RUNNING = "running"
        const val START = "kalshi.pattern.START"
        const val STOP = "kalshi.pattern.STOP"
        private const val BASE = "https://api.binance.us/api/v3/klines"
        private const val TARGET_MARKETS = 1000
        private const val MAX_PAGES = 80
        private const val MINUTE_MS = 60_000L
        private const val QUARTER_MS = 15L * MINUTE_MS
        private const val CHANNEL = "binance_27_pattern_backtest"
        private const val NOTIFICATION_ID = 7877
    }

    private val active = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(
                CHANNEL, "Binance BTC backtest", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, KalshiPatternResultsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Binance BTC historical backtest")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true).build()
    }

    private fun status(message: String) {
        prefs.edit().putString(STATUS, message).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, notification(message))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            active.set(false)
            status("Stopping Binance BTC test...")
            return START_NOT_STICKY
        }
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification("Preparing Binance BTC test..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification("Preparing Binance BTC test..."))
        prefs.edit().putBoolean(RUNNING, true)
            .putString(REPORT, "").putString(STATUS, "Preparing Binance BTC data...")
            .apply()
        Thread {
            try {
                scan()
            } catch (e: Exception) {
                status("ERROR: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                active.set(false)
                prefs.edit().putBoolean(RUNNING, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun getCandles(endTimeMs: Long): List<BtcAcmMath.Candle> {
        var lastError = ""
        for (attempt in 0..3) {
            if (!active.get()) error("Scan stopped")
            val url = "$BASE?symbol=BTCUSDT&interval=1m&limit=1000&endTime=$endTimeMs"
            val response = client.newCall(Request.Builder()
                .url(url).header("Accept", "application/json").get().build())
                .execute().use { result ->
                    val body = result.body?.string().orEmpty()
                    if (result.isSuccessful) return@use Pair(result.code, body)
                    lastError = "Binance.US HTTP ${result.code}: ${body.take(160)}"
                    Pair(result.code, "")
                }
            if (response.first == 200) {
                val arr = JSONArray(response.second)
                val candles = ArrayList<BtcAcmMath.Candle>(arr.length())
                for (i in 0 until arr.length()) {
                    val row = arr.getJSONArray(i)
                    val candle = BtcAcmMath.Candle(
                        row.getLong(0), row.getDouble(1), row.getDouble(2),
                        row.getDouble(3), row.getDouble(4))
                    if (candle.valid() && candle.at <= endTimeMs)
                        candles.add(candle)
                }
                return candles
            }
            if (response.first != 429 && response.first !in 500..599) break
            Thread.sleep(1500L * (attempt + 1))
        }
        error(lastError.ifBlank { "Binance.US candle download failed" })
    }


    private fun buildMarket(
        start: Long, candles: Map<Long, BtcAcmMath.Candle>
    ): BinanceTwentySevenPatternReport.Market? {
        if (!BinanceTwentySevenPatternReport.isAllowedSession(start)) return null
        // Require all 60 prior warmup AND all 15 session candles.
        // No missing candle interpolation and no future information.
        for (offset in -60..14) {
            if (candles[start + offset * MINUTE_MS] == null) return null
        }
        val target = candles.getValue(start - MINUTE_MS).close
        val finalBtc = candles.getValue(start + 14L * MINUTE_MS).close
        if (target <= 0.0 || finalBtc == target) return null
        val series = ArrayList<BinanceTwentySevenPatternReport.Observation>(14)
        for (minute in 1..14) {
            val candleOpen = start + (minute - 1L) * MINUTE_MS
            val candle = candles.getValue(candleOpen)
            val acm60 = BtcAcmMath.averageRange(candles, candleOpen, 60)
                ?: return null
            val left = (15 - minute).toDouble()
            val signed = BtcChartProxyMath.signedStrength(
                candle.close, target, acm60, left)
            if (!signed.isFinite()) return null
            series.add(BinanceTwentySevenPatternReport.Observation(minute, signed))
        }
        return BinanceTwentySevenPatternReport.Market(
            start, target, finalBtc, series)
    }

    private fun scan() {
        status("Downloading Binance BTC 1-minute candles for 1,000 markets...")
        val candles = HashMap<Long, BtcAcmMath.Candle>()
        val selected = LinkedHashMap<Long, BinanceTwentySevenPatternReport.Market>()
        // Keep the 1,000-market validation completely separate from the
        // Nov 17-19 discovery period; do not include the discovery days.
        var endTime = java.time.LocalDate.of(2026, 11, 17)
            .atStartOfDay(java.time.ZoneId.of("America/New_York"))
            .toInstant().toEpochMilli() - 1L
        var previousOldest = Long.MAX_VALUE
        var pageCount = 0
        for (page in 1..MAX_PAGES) {
            if (!active.get()) error("Scan stopped")
            val batch = getCandles(endTime)
            if (batch.isEmpty()) break
            for (c in batch) candles[c.at] = c
            val oldest = batch.minOf { it.at }
            if (oldest >= previousOldest) break
            previousOldest = oldest
            endTime = oldest - 1L
            pageCount = page

            val starts = candles.keys.asSequence()
                .filter { it % QUARTER_MS == 0L }
                .sortedDescending().toList()
            for (start in starts) {
                if (selected.containsKey(start)) continue
                // Market must have fully finished.
                if (start + QUARTER_MS >
                    System.currentTimeMillis() / MINUTE_MS * MINUTE_MS) continue
                val market = buildMarket(start, candles) ?: continue
                selected[start] = market
            }
            if (selected.size >= TARGET_MARKETS) break
            status("Binance BTC historical pages: $page; complete 15m " +
                "markets: ${selected.size}/$TARGET_MARKETS")
            try { Thread.sleep(140L) } catch (_: InterruptedException) { break }
        }
        if (selected.isEmpty())
            error("No complete Binance BTC 15-minute markets found. " +
                "Check Binance.US connectivity.")

        val chosen = selected.values.sortedByDescending { it.startMs }
            .take(TARGET_MARKETS).sortedBy { it.startMs }
        val report = BinanceTwentySevenPatternReport.report(chosen)
        val complete = chosen.size == TARGET_MARKETS
        val summary = if (complete)
            "COMPLETE: 1,000 Binance BTC 15-minute markets; 27 existing rules and 7 target-centered paths tested."
        else
            "INCOMPLETE: ${chosen.size}/1,000 Binance BTC sections after " +
                "$pageCount download pages. Results shown provisionally."
        prefs.edit().putString(REPORT, report).putString(STATUS, summary).apply()
    }

    override fun onDestroy() {
        active.set(false)
        super.onDestroy()
    }
}
