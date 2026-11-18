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
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only background watch of REAL Kalshi KXBTC15M bid/ask and 1-minute
 * candlestick closes. Never submits an order or infers Kalshi quotes from BTC.
 */
class KalshiLiveSignalService : Service() {
    companion object {
        const val PREFS = "kalshi_live_signals_v1"
        const val ENABLED = "enabled"
        const val STATUS = "status"
        const val LAST_ALERT = "last_alert"
        const val START = "kalshi.live.START"
        const val STOP = "kalshi.live.STOP"
        private const val BASE = "https://external-api.kalshi.com/trade-api/v2"
        private const val SERIES = "KXBTC15M"
        private const val RUNNING_CHANNEL = "kalshi_live_monitor_v1"
        private const val SIGNAL_CHANNEL = "kalshi_live_path_signal_v1"
        private const val RUNNING_ID = 7910
        private const val MINUTE = 60L
        private const val QUARTER = 15L * MINUTE
    }

    private val working = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private val tz = ZoneId.of("America/New_York")
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notifications = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notifications.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL,
            "Kalshi path watch running", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(SIGNAL_CHANNEL,
            "Kalshi researched path alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Live quote paths with estimated fee-adjusted historical advantage"
            enableVibration(true)
        })
    }

    private fun viewIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, BtcRangeBacktestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun runningNotification(message: String): Notification =
        NotificationCompat.Builder(this, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Kalshi path alerts active")
            .setContentText(message)
            .setContentIntent(viewIntent())
            .setOnlyAlertOnce(true).setOngoing(true).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            prefs.edit().putBoolean(ENABLED, false).putString(STATUS,
                "Stopped. Re-enable notifications on the BTC chart.").apply()
            working.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START && !prefs.getBoolean(ENABLED, true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean(ENABLED, true).apply()
        if (!working.compareAndSet(false, true)) return START_STICKY
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(RUNNING_ID, runningNotification("Looking for live BTC markets"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(RUNNING_ID,
            runningNotification("Looking for live BTC markets"))
        Thread {
            try {
                while (working.get()) {
                    try { inspect() }
                    catch (e: Exception) {
                        status("Kalshi connection: " +
                            (e.message ?: e.javaClass.simpleName).take(160))
                    }
                    try { Thread.sleep(20_000L) }
                    catch (_: InterruptedException) { break }
                }
            } finally {
                working.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun status(s: String) {
        prefs.edit().putString(STATUS, s).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RUNNING_ID, runningNotification(s))
    }

    private fun json(url: String): JSONObject =
        client.newCall(Request.Builder().url(url)
            .header("Accept", "application/json").get().build())
            .execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful)
                    error("HTTP " + response.code + ": " + body.take(90))
                JSONObject(body)
            }

    private data class Market(
        val ticker: String, val close: Long, val bid: Double, val ask: Double
    )

    private fun marketPrice(obj: JSONObject, dollars: String, legacy: String): Double {
        val newer = obj.optString(dollars).toDoubleOrNull()
        if (newer != null) return newer
        val cents = obj.optDouble(legacy, Double.NaN)
        return if (cents.isFinite()) cents / 100.0 else Double.NaN
    }

    private fun currentMarket(now: Long): Market? {
        val result = json("$BASE/markets?series_ticker=$SERIES&status=open&limit=100")
        val listed = result.optJSONArray("markets") ?: JSONArray()
        val matches = ArrayList<Market>()
        for (index in 0 until listed.length()) {
            val m = listed.optJSONObject(index) ?: continue
            val ticker = m.optString("ticker")
            if (!ticker.startsWith("$SERIES-")) continue
            val close = try {
                Instant.parse(m.optString("close_time")).epochSecond
            } catch (_: Exception) { continue }
            if (close - now !in 1L..QUARTER) continue
            // Only the 10:00 through 22:45 session starts, Eastern time.
            val startHour = Instant.ofEpochSecond(close - QUARTER).atZone(tz).hour
            if (startHour !in 10..22) continue
            val bid = marketPrice(m, "yes_bid_dollars", "yes_bid")
            val ask = marketPrice(m, "yes_ask_dollars", "yes_ask")
            if (!bid.isFinite() || !ask.isFinite()) continue
            matches.add(Market(ticker, close, bid, ask))
        }
        return matches.minByOrNull { it.close }
    }

    private fun price(c: JSONObject?, name: String): Double? {
        if (c == null) return null
        return c.optString(name + "_dollars").ifBlank {
            c.optString(name)
        }.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    private fun getCandles(market: Market, now: Long): List<KalshiPatternMath.Quote> {
        val start = market.close - QUARTER
        val url = "$BASE/series/$SERIES/markets/" + market.ticker +
            "/candlesticks?start_ts=$start&end_ts=$now&period_interval=1"
        val data = json(url).optJSONArray("candlesticks") ?: JSONArray()
        val result = ArrayList<KalshiPatternMath.Quote>()
        for (i in 0 until data.length()) {
            val candle = data.optJSONObject(i) ?: continue
            val end = candle.optLong("end_period_ts")
            if (end <= 0L || end > now - 5 || end <= start ||
                (market.close - end) % MINUTE != 0L) continue
            val left = ((market.close - end) / MINUTE).toInt()
            val bid = price(candle.optJSONObject("yes_bid"), "close") ?: continue
            val ask = price(candle.optJSONObject("yes_ask"), "close") ?: continue
            val quote = KalshiPatternMath.Quote(left, bid, ask)
            if (quote.valid()) result.add(quote)
        }
        return result.sortedByDescending { it.left }.distinctBy { it.left }
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun cents(v: Double) =
        String.format(Locale.US, "%.0f", 100.0 * v) + "c"

    private fun inspect() {
        val now = System.currentTimeMillis() / 1000L
        val market = currentMarket(now)
        if (!working.get()) return
        if (market == null) {
            status("Waiting for an open BTC 15m market (10 AM–11 PM ET)")
            return
        }
        val history = getCandles(market, now)
        val newest = history.lastOrNull()
        if (newest == null) {
            status("Waiting for live 1-minute Kalshi quotes")
            return
        }
        val lastCandleTime = market.close - newest.left * MINUTE
        if (now - lastCandleTime > 90L) {
            status("Kalshi 1-minute quotes are stale; no alert")
            return
        }
        val lastSentTicker = prefs.getString("sent_ticker", null)
        if (lastSentTicker == market.ticker) {
            status("Already alerted this BTC market; waiting for next")
            return
        }
        val matched = KalshiLiveSignalRules.matchingSignals(
            history, market.bid, market.ask)
        if (matched.isEmpty()) {
            status("Watching " + market.ticker.takeLast(16) + " | " +
                newest.left + "m | no qualifying edge")
            return
        }
        val best = matched.first()
        if (!working.get()) return
        // Reserve ONE alert per market, even if several researched paths overlap.
        prefs.edit().putString("sent_ticker", market.ticker).apply()
        val rule = best.rule
        val title = "Kalshi WATCH: " + rule.side + " " + rule.name
        val detail = rule.side + " ask " + cents(best.liveAsk) +
            " + fee ~" + cents(best.fee) + " | historical " +
            fmt(rule.newestWinRate * 100) + "% (" + rule.newestN +
            " newer) | edge ~+" + fmt(best.estimatedEdge * 100) +
            "pp | " + best.left + "m left"
        prefs.edit().putString(LAST_ALERT, title + "\n" + detail)
            .putString(STATUS, "Last alert: " + title).apply()

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail +
                "\nResearch only; not a guaranteed win or executable price."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(viewIntent())
            .setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(8100 + ((market.close / QUARTER) % 100000L).toInt(), notification)
    }

    override fun onDestroy() {
        working.set(false)
        super.onDestroy()
    }
}
