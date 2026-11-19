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
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Background-only historical scan. Makes requests directly to Kalshi public
 * endpoints; Binance-derived chart values are never used as Kalshi odds.
 */
class KalshiPatternBacktestService : Service() {
    companion object {
        const val PREFS = "kalshi_pattern_scan_v1"
        const val STATUS = "status"
        const val REPORT = "report"
        const val RUNNING = "running"
        const val START = "kalshi.pattern.START"
        const val STOP = "kalshi.pattern.STOP"
        private const val BASE = "https://external-api.kalshi.com/trade-api/v2"
        private const val SERIES = "KXBTC15M"
        private const val TARGET = 100
        // Extra candidates replace markets with missing or unusable quote history.
        private const val CANDIDATE_TARGET = 500
        private const val CHANNEL = "kalshi_pattern_backtest"
        private const val NOTIFICATION_ID = 7877
    }

    private val active = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .build()
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private data class Listing(val ticker: String, val closeTs: Long,
        val yesWon: Boolean, val archived: Boolean)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(
                CHANNEL, "Historical Kalshi test",
                NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, KalshiPatternResultsActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Kalshi historical backtest")
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
            status("Stopping historical test...")
            return START_NOT_STICKY
        }
        if (!active.compareAndSet(false, true)) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification("Preparing historical scan..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification("Preparing historical scan..."))
        prefs.edit().putBoolean(RUNNING, true)
            .putString(REPORT, "").putString(STATUS, "Preparing...").apply()
        Thread {
            try {
                scan()
            } catch (e: Exception) {
                status("ERROR: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                active.set(false)
                prefs.edit().putBoolean(RUNNING, false).apply()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun getJson(url: String): JSONObject {
        var last = ""
        for (attempt in 0..3) {
            if (!active.get()) error("Scan canceled")
            val result = client.newCall(Request.Builder()
                .url(url).header("Accept", "application/json").get().build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (response.isSuccessful) return JSONObject(text)
                    last = "Kalshi HTTP ${response.code}: ${text.take(160)}"
                    response.code
                }
            if (result != 429 && result !in 500..599) break
            Thread.sleep(1500L * (attempt + 1))
        }
        error(last)
    }

    private fun listMarkets(archived: Boolean): List<Listing> {
        val endpoint = if (archived) "historical/markets" else "markets"
        val result = ArrayList<Listing>()
        var cursor = ""
        // Paginate if necessary, but never loop indefinitely.
        repeat(12) {
            if (!active.get() || result.size >= CANDIDATE_TARGET) return result
            val base = "$BASE/$endpoint?series_ticker=$SERIES&limit=1000"
            val url = buildString {
                append(base)
                if (!archived) append("&status=settled")
                if (cursor.isNotEmpty())
                    append("&cursor=").append(java.net.URLEncoder.encode(cursor, "UTF-8"))
            }
            val json = getJson(url)
            val items = json.optJSONArray("markets") ?: JSONArray()
            for (i in 0 until items.length()) {
                val market = items.optJSONObject(i) ?: continue
                val ticker = market.optString("ticker")
                val resultString = market.optString("result").lowercase()
                if (!ticker.startsWith("$SERIES-") ||
                    resultString !in listOf("yes", "no")) continue
                val closeTs = try {
                    Instant.parse(market.optString("close_time")).epochSecond
                } catch (_: Exception) { continue }
                // Never include a current/future market as historical evidence.
                if (closeTs < 900 || closeTs >= Instant.now().epochSecond) continue
                // Include 10:00 AM through 10:45 PM market starts, in Eastern time.
                // DST is handled by the America/New_York IANA time zone.
                if (!KalshiHundredMarketReport.isAllowedSession(closeTs)) continue
                result.add(Listing(ticker, closeTs, resultString == "yes", archived))
            }
            val next = json.optString("cursor")
            if (next.isBlank() || next == cursor) return result
            cursor = next
        }
        return result
    }

    private fun price(obj: JSONObject?, key: String): Double? {
        if (obj == null) return null
        // The historical API supports the newer *_dollars names, and older
        // historical responses can still contain the unsuffixed OHLC fields.
        val a = obj.optString("${key}_dollars", "")
            .ifBlank { obj.optString(key, "") }
        return a.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /**
     * A minute candle averages its four YES bid OHLC prices and four YES ask
     * OHLC prices when present; when not present, keep the observed close
     * midpoint instead. Do NOT interpolate a missing minute.
     */
    private fun averageYesPercent(c: JSONObject): Pair<Double, Boolean>? {
        val yesBid = c.optJSONObject("yes_bid") ?: return null
        val yesAsk = c.optJSONObject("yes_ask") ?: return null
        val bidClose = price(yesBid, "close") ?: return null
        val askClose = price(yesAsk, "close") ?: return null
        fun good(bid: Double, ask: Double) =
            bid in 0.0..1.0 && ask in 0.0..1.0 &&
                bid <= ask && ask - bid <= 0.25
        if (!good(bidClose, askClose)) return null

        val fields = listOf("open", "high", "low", "close")
        val all = fields.map { key -> price(yesBid, key) to price(yesAsk, key) }
        val enough = all.all { (bid, ask) ->
            bid != null && ask != null && good(bid, ask)
        }
        if (enough) {
            val averageBid = all.map { it.first!! }.average()
            val averageAsk = all.map { it.second!! }.average()
            return (100.0 * (averageBid + averageAsk) / 2.0) to false
        }
        return (100.0 * (bidClose + askClose) / 2.0) to true
    }

    private fun load(listing: Listing): KalshiHundredMarketReport.Market? {
        val from = listing.closeTs - KalshiPatternMath.QUARTER_SECONDS
        val endpoint = if (listing.archived)
            "historical/markets/${listing.ticker}/candlesticks"
        else "series/$SERIES/markets/${listing.ticker}/candlesticks"
        val json = getJson("$BASE/$endpoint?start_ts=$from&end_ts=${listing.closeTs}&period_interval=1")
        val candles = json.optJSONArray("candlesticks") ?: return null
        val points = ArrayList<KalshiHundredMarketReport.MinutePrice>()
        for (i in 0 until candles.length()) {
            val c = candles.optJSONObject(i) ?: continue
            val end = c.optLong("end_period_ts", 0L)
            // Fifteen completed minute candles end at start+60 through close;
            // minute 15 is the candle ending at close (not minutes left = 0).
            val elapsed = end - from
            if (elapsed !in 60L..900L || elapsed % 60L != 0L) continue
            val minute = (elapsed / 60L).toInt()
            val (percentage, usedClose) = averageYesPercent(c) ?: continue
            if (percentage !in 0.0..100.0) continue
            points.add(KalshiHundredMarketReport.MinutePrice(
                minute, percentage, usedClose))
        }
        if (points.size < 2) return null
        val unique = points.sortedBy { it.minute }.distinctBy { it.minute }
        return KalshiHundredMarketReport.Market(
            listing.ticker, listing.closeTs, listing.yesWon, unique)
    }

    private fun scan() {
        status("Collecting 100 settled BTC 15-minute markets (10 AM-11 PM ET)...")
        val candidates = LinkedHashMap<String, Listing>()
        var discoveryFailure = ""
        try {
            for (m in listMarkets(false)) candidates[m.ticker] = m
        } catch (e: Exception) {
            discoveryFailure = e.message ?: "Recent market listing unavailable"
        }
        if (candidates.size < CANDIDATE_TARGET && active.get()) {
            status("Checking the Kalshi historical archive...")
            try {
                for (m in listMarkets(true)) candidates.putIfAbsent(m.ticker, m)
            } catch (e: Exception) {
                if (candidates.isEmpty()) discoveryFailure =
                    e.message ?: "Historical market listing unavailable"
            }
        }
        val chosen = candidates.values.sortedByDescending { it.closeTs }.take(CANDIDATE_TARGET)
        if (chosen.isEmpty()) error("No settled $SERIES markets returned. $discoveryFailure")
        val observed = ArrayList<KalshiHundredMarketReport.Market>()
        var skipped = 0
        var errors = 0
        var firstError = ""
        for ((index, item) in chosen.withIndex()) {
            if (!active.get() || observed.size >= TARGET) break
            try {
                val market = load(item)
                if (market != null) observed.add(market) else skipped++
                errors = 0
            } catch (e: Exception) {
                skipped++
                errors++
                if (firstError.isBlank()) firstError = e.message.orEmpty()
                // Don't run hundreds of failing calls when network/API is blocked.
                if (errors >= 12) {
                    status("Stopped after repeated Kalshi errors: $firstError")
                    break
                }
            }
            if ((index + 1) % 10 == 0 || index + 1 == chosen.size) {
                status("Valid settled past markets: ${observed.size}/$TARGET; checked ${index + 1}/${chosen.size}; skipped $skipped")
            }
            try { Thread.sleep(180L) } catch (_: InterruptedException) { break }
        }
        if (observed.isEmpty()) error("No valid quote candles found. $firstError")
        val count = observed.map { it.ticker }.toSet().size
        val complete = count == TARGET && observed.size == TARGET
        val result = KalshiHundredMarketReport.report(observed, skipped)
        val finalStatus = if (complete)
            "COMPLETE: 100/100 distinct settled BTC markets collected."
        else
            "INCOMPLETE: $count/100 valid past markets; skipped $skipped. " +
                "Do NOT treat this as a 100-market discovery dataset. " + firstError.take(90)
        prefs.edit().putString(REPORT, result)
            .putString(STATUS, finalStatus)
            .apply()
    }

    override fun onDestroy() {
        active.set(false)
        super.onDestroy()
    }
}
