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
import java.time.ZoneId
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
        private const val TARGET = 1000
        // Extra candidates replace markets with missing or unusable quote history.
        private const val CANDIDATE_TARGET = 3000
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
                val localHour = Instant.ofEpochSecond(closeTs - 900L)
                    .atZone(ZoneId.of("America/New_York")).hour
                if (localHour !in 10..22) continue
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

    private fun load(listing: Listing): KalshiPatternMath.Market? {
        val from = listing.closeTs - KalshiPatternMath.QUARTER_SECONDS
        val endpoint = if (listing.archived)
            "historical/markets/${listing.ticker}/candlesticks"
        else "series/$SERIES/markets/${listing.ticker}/candlesticks"
        val json = getJson("$BASE/$endpoint?start_ts=$from&end_ts=${listing.closeTs}&period_interval=1")
        val candles = json.optJSONArray("candlesticks") ?: return null
        val points = ArrayList<KalshiPatternMath.Quote>()
        for (i in 0 until candles.length()) {
            val c = candles.optJSONObject(i) ?: continue
            val end = c.optLong("end_period_ts", 0L)
            val delta = listing.closeTs - end
            if (delta % 60L != 0L) continue
            val left = (delta / 60L).toInt()
            val bid = price(c.optJSONObject("yes_bid"), "close") ?: continue
            val ask = price(c.optJSONObject("yes_ask"), "close") ?: continue
            val observation = KalshiPatternMath.Quote(left, bid, ask)
            if (observation.valid()) points.add(observation)
        }
        if (points.size < 2) return null
        return KalshiPatternMath.Market(listing.ticker,
            listing.closeTs, listing.yesWon, points)
    }

    private fun scan() {
        status("Finding 1,000 PAST settled BTC markets (10 AM-11 PM ET)...")
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
        val observed = ArrayList<KalshiPatternMath.Market>()
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
        val result = KalshiFourPatternReport.report(observed, skipped)
        val finalStatus = if (complete)
            "COMPLETE: 1000/1000 distinct settled past BTC markets verified."
        else
            "INCOMPLETE: $count/1000 valid past markets; skipped $skipped. " +
                "Do NOT treat this as a 1,000-market result. " + firstError.take(90)
        prefs.edit().putString(REPORT, result)
            .putString(STATUS, finalStatus)
            .apply()
    }

    override fun onDestroy() {
        active.set(false)
        super.onDestroy()
    }
}
