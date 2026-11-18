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
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only live monitoring. One notification max per Kalshi 15m market.
 * No API keys, authentication, order creation or purchases.
 * Past 21/27 wins is a SAMPLE, not a validated current win probability.
 */
class BtcAcmLiveService : Service() {
    companion object {
        const val PREFS = "btc_acm_live_v1"
        const val START = "btc.acm.live.START"
        const val STOP = "btc.acm.live.STOP"
        private const val SERVICE_CHANNEL = "btc_acm_live_status_v1"
        private const val BUY_CHANNEL = "btc_acm_buy_alerts_v1"
        private const val FOREGROUND_ID = 9411
        private const val BUY_ID = 9412
        private const val MINUTE = 60_000L
        private const val SLEEP_MS = 10_000L
    }
    private val running = AtomicBoolean(false)
    private val canceled = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(9, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(24, TimeUnit.SECONDS)
        .build()

    private data class Market(
        val ticker: String,
        val target: Double,
        val closeMs: Long,
        val yesAsk: Double?,
        val noAsk: Double?,
        val yesAskSize: Double?,
        val noAskSize: Double?
    )
    private data class History(
        val lastMinute: Long, val candles: List<BtcAcmMath.Candle>,
        val acmPct: Double, val chart: String,
        val allCandles: Map<Long, BtcAcmMath.Candle>
    )
    private var cachedHistory: History? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            SERVICE_CHANNEL, "Live BTC ACM monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(
            BUY_CHANNEL, "BTC/Kalshi BUY opportunities", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            canceled.set(true)
            save("stopping", "Stopping live monitor. No further BUY alerts.")
            if (!running.get()) {
                save("stopped", "Live monitor stopped.")
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_STICKY
        canceled.set(false)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(FOREGROUND_ID, foreground("Watching BTC and Kalshi"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(FOREGROUND_ID, foreground("Watching BTC and Kalshi"))
        save("starting", "Connecting to BTC 1m candles and Kalshi live asks.", "")
        Thread {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "decisionoverlay:btcAcmLive")
            lock.setReferenceCounted(false)
            try {
                while (!canceled.get()) {
                    if (!lock.isHeld) lock.acquire(60L * 60L * 1000L)
                    try {
                        checkLive()
                    } catch (e: Exception) {
                        if (!canceled.get()) {
                            val note = (e.message ?: e.javaClass.simpleName).take(170)
                            save("running", "Data unavailable — no BUY alert: $note",
                                "WAIT — could not verify fresh BTC/Kalshi prices.\n$note")
                        }
                    }
                    if (!canceled.get()) Thread.sleep(SLEEP_MS)
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                if (lock.isHeld) lock.release()
                save("stopped", "Live BTC monitor stopped.")
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun save(phase: String, status: String, report: String? = null,
        history: History? = null, target: Double? = null,
        curve: String? = null,
        upper: Double? = null, lower: Double? = null, spot: Double? = null
    ) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", phase).putString("status", status)
        if (report != null) editor.putString("report", report)
        if (history != null) editor.putString("chart", history.chart)
        if (curve != null) editor.putString("curve", curve)
        if (target != null) editor.putFloat("target", target.toFloat())
        if (upper != null) editor.putFloat("up", upper.toFloat())
        if (lower != null) editor.putFloat("down", lower.toFloat())
        if (spot != null) editor.putFloat("spot", spot.toFloat())
        editor.apply()
    }

    private fun foreground(message: String): Notification {
        val open = PendingIntent.getActivity(this, FOREGROUND_ID,
            Intent(this, BtcAcmLiveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, SERVICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC 60-candle ACM / Kalshi monitor")
            .setContentText(message).setOngoing(true)
            .setContentIntent(open).setOnlyAlertOnce(true).build()
    }

    private fun notifyBuy(market: Market, decision: BtcAcmSignal.Decision) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString("last_alerted_ticker", "") == market.ticker) return
        val open = PendingIntent.getActivity(this, BUY_ID,
            Intent(this, BtcAcmLiveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val body = String.format(Locale.US,
            "%s @ %.1fc; %.1fm left; BTC $%,.2f / target $%,.2f. Fee $%.2f; " +
                "%d/27 prior wins (unverified). RECHECK ASK.",
            decision.direction, (decision.ask ?: 0.0) * 100.0,
            decision.secondsLeft / 60.0, decision.spot, market.target,
            decision.fee, BtcAcmSignal.HISTORICAL_WINS)
        val item = NotificationCompat.Builder(this, BUY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BUY " + decision.direction + " — Kalshi 60–69¢ [TEST]")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                body + "\n" + market.ticker +
                    "\nExperimental 100-market result; market may change before filling."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(open).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(BUY_ID, item)
        prefs.edit().putString("last_alerted_ticker", market.ticker).apply()
    }

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).get().build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful)
                error("HTTP " + it.code + ": " + body.take(130))
            require(body.isNotBlank()) { "Empty API response" }
            body
        }

    private fun ask(v: JSONObject, name: String): Double? =
        v.optString(name, "").toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.01 && it <= 0.99 }

    private fun size(v: JSONObject, name: String): Double? =
        v.optString(name, "").toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }

    private fun liveMarket(now: Long): Market {
        val data = JSONObject(fetch(
            "https://external-api.kalshi.com/trade-api/v2/markets" +
                "?series_ticker=KXBTC15M&status=open&limit=100"))
        val all = data.optJSONArray("markets") ?: error("Kalshi markets array missing")
        return (0 until all.length()).mapNotNull { i ->
            val m = all.optJSONObject(i) ?: return@mapNotNull null
            val ticker = m.optString("ticker")
            if (!ticker.startsWith("KXBTC15M-")) return@mapNotNull null
            val close = runCatching {
                Instant.parse(m.getString("close_time")).toEpochMilli()
            }.getOrNull() ?: return@mapNotNull null
            val start = close - 15L * MINUTE
            val target = m.optDouble("floor_strike", Double.NaN)
            if (!target.isFinite() || target <= 0.0 || now < start ||
                now >= close || close - now > 15L * MINUTE ||
                !TradingHours.allowedMarketStart(start))
                return@mapNotNull null
            Market(ticker, target, close,
                ask(m, "yes_ask_dollars"), ask(m, "no_ask_dollars"),
                size(m, "yes_ask_size_fp"), size(m, "no_ask_size_fp"))
        }.minByOrNull { it.closeMs }
            ?: error("No active BTC15M with Kalshi target during 10 AM–11 PM ET")
    }

    private fun history(now: Long): History {
        // Current 1m candle is still open, and must NOT be used for ACM.
        val lastCompletedMinute = now / MINUTE * MINUTE - MINUTE
        cachedHistory?.let {
            if (it.lastMinute == lastCompletedMinute) return it
        }
        val end = lastCompletedMinute + MINUTE - 1L
        val data = JSONArray(fetch(
            "https://api.binance.us/api/v3/klines" +
                "?symbol=BTCUSDT&interval=1m&limit=90&endTime=$end"))
        val map = mutableMapOf<Long, BtcAcmMath.Candle>()
        for (i in 0 until data.length()) {
            val a = data.getJSONArray(i)
            val c = BtcAcmMath.Candle(a.getLong(0), a.getDouble(1),
                a.getDouble(2), a.getDouble(3), a.getDouble(4))
            if (c.valid()) map[c.at] = c
        }
        val candles = (59 downTo 0).map { offset ->
            map[lastCompletedMinute - offset * MINUTE]
                ?: error("Last 60 completed BTC candles missing at minute $offset")
        }
        val acm = BtcAcmMath.averageRange(map, lastCompletedMinute, 60)
            ?: error("Cannot calculate ACM60 from complete consecutive candles")
        require(acm > 0.0 && acm.isFinite()) { "ACM60 unavailable" }
        val plot = JSONArray()
        for (c in candles) plot.put(JSONArray().apply {
            put(c.at); put(c.open); put(c.high); put(c.low); put(c.close)
        })
        return History(lastCompletedMinute, candles, acm, plot.toString(), map)
            .also { cachedHistory = it }
    }

    private fun checkLive() {
        val current = System.currentTimeMillis()
        val market = liveMarket(current)
        val history = history(System.currentTimeMillis())
        val spot = JSONObject(fetch(
            "https://api.binance.us/api/v3/ticker/price?symbol=BTCUSDT"))
            .getString("price").toDouble()
        require(spot.isFinite() && spot > 0.0) { "Invalid BTC ticker price" }
        val left = (market.closeMs - System.currentTimeMillis()) / 1000.0
        val decision = BtcAcmSignal.evaluate(spot, market.target,
            history.acmPct, left, market.yesAsk, market.noAsk)
        val priceSize = if (decision.direction == "YES")
            market.yesAskSize else market.noAskSize
        val enoughAskSize = priceSize != null &&
            decision.contracts > 0 && priceSize + 1e-8 >= decision.contracts
        // Actual top of book must have the quoted number of contracts for $10.
        val qualifies = decision.buyEligible && enoughAskSize &&
            !canceled.get() && System.currentTimeMillis() < market.closeMs
        if (qualifies) notifyBuy(market, decision)

        val bound = BtcAcmCountdown.boundaries(market.target, history.acmPct,
            (left * 1000.0).toLong())
        val upper = bound.first
        val lower = bound.second
        val curve = JSONArray()
        for (point in BtcAcmCountdown.series(history.allCandles, market.target,
                market.closeMs, System.currentTimeMillis(), history.acmPct, spot)) {
            curve.put(JSONArray().apply {
                put(point.at); put(point.upper); put(point.lower)
                if (point.btcPrice == null) put(JSONObject.NULL) else put(point.btcPrice)
                put(point.projected)
            })
        }
        val description = if (decision.buyEligible && !enoughAskSize)
            "WAIT — price qualifies but Kalshi ask size is unavailable or insufficient"
        else if (qualifies) "BUY " + decision.direction +
            " — alert eligible, recheck ask before order"
        else decision.reason
        val report = buildString {
            appendLine("BTC / KALSHI — LIVE ACM60")
            appendLine("Market: " + market.ticker)
            appendLine(String.format(Locale.US,
                "BTC live $%,.2f | Kalshi TARGET $%,.2f | %s",
                spot, market.target, decision.direction))
            appendLine(String.format(Locale.US,
                "Time remaining %.1f seconds (display bucket %d; trigger uses exact seconds)",
                left, decision.minutesBucket))
            appendLine(String.format(Locale.US,
                "ACM60 (last 60 COMPLETE 1m high-low ranges): %.5f%%",
                history.acmPct))
            appendLine(String.format(Locale.US,
                "K %.2f | required gap %.4f%% | actual gap %.4f%%",
                BtcAcmSignal.MULTIPLIER, decision.requiredPct, decision.gapPct))
            appendLine(String.format(Locale.US,
                "BTC trigger: BUY YES if above $%,.2f; BUY NO if below $%,.2f",
                upper, lower))
            appendLine(String.format(Locale.US,
                "Kalshi asks: YES %s | NO %s | selected %s",
                cents(market.yesAsk), cents(market.noAsk), cents(decision.ask)))
            appendLine(String.format(Locale.US,
                "Selected order-book ask size: %s contracts | %d needed",
                if (priceSize == null) "UNAVAILABLE" else
                    String.format(Locale.US, "%.2f", priceSize),
                decision.contracts))
            appendLine("TESTED BUY RANGE: 60–69¢; Kalshi ask + fee must beat sample win rate.")
            appendLine(String.format(Locale.US,
                "Past K0.25 / 60–69c: %d wins / %d historical trades = %.1f%%",
                BtcAcmSignal.HISTORICAL_WINS, BtcAcmSignal.HISTORICAL_ENTRIES,
                BtcAcmSignal.sampleWinRate * 100.0))
            appendLine("That 77.8% is a tiny, selected in-sample rate, NOT verified odds.")
            if (decision.contracts > 0) {
                appendLine(String.format(Locale.US,
                    "UP TO $10: %d contracts, estimated general taker fee $%.2f, " +
                        "total $%.2f, win profit $%.2f, loss $%.2f",
                    decision.contracts, decision.fee, decision.totalCost,
                    decision.estimatedProfitIfWin, decision.totalCost))
                appendLine(String.format(Locale.US,
                    "Fee-adjusted break-even %.1f%% | historical-sample margin %+.1f pp " +
                        "(needs at least %.1f pp)",
                    decision.breakEven * 100.0, decision.rawEdgePercentagePoints,
                    BtcAcmSignal.MIN_EDGE_PP))
            } else appendLine("Fee/break-even unavailable without a live valid ask.")
            appendLine()
            appendLine("DECISION: " + description)
            appendLine("One alert max per Kalshi 15-minute market. Final minute blocked.")
            appendLine("No order placed; quotes can move before you act.")
            appendLine("Kalshi's actual settlement index differs from Binance.US spot.")
            appendLine("Prior backtest did NOT validate this edge out of sample.")
        }
        val phase = "running"
        save(phase, if (qualifies) "BUY " + decision.direction +
            " test condition met — see notification."
            else "Monitoring BTC/Kalshi: " + description,
            report, history, market.target, curve.toString(), upper, lower, spot)
    }

    private fun cents(value: Double?): String =
        if (value == null) "UNAVAILABLE"
        else String.format(Locale.US, "%.2f¢", value * 100.0)
}
