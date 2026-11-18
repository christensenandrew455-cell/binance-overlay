package com.decisionoverlay.checker

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Read-only study; no orders. Binance.US drives signals, Kalshi market result
 * drives wins, historical Kalshi ask/bid candles give indicative entry costs.
 */
class BtcAcmProfitService : Service() {
    companion object {
        const val PREFS = "btc_acm_profit_v1"
        const val START = "btc.profit.START"
        const val STOP = "btc.profit.STOP"
        private const val CHANNEL = "btc_acm_profit_progress"
        private const val COMPLETE_CHANNEL = "btc_acm_profit_complete"
        private const val ID = 9361
        private const val MINUTE = 60000L
        private const val API = "https://external-api.kalshi.com/trade-api/v2"
    }
    private val zone = ZoneId.of("America/New_York")
    private val cancel = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS).build()
    private data class Market(val ticker: String, val closeMs: Long, val yesWins: Boolean)
    private data class Quote(val ask: Double?, val bid: Double?)
    private data class Trade(val price: Double, val fee: Double, val cost: Double, val pnl: Double)
    private data class Stat(val k: Double, val band: Int, var trades: Int = 0,
        var wins: Int = 0, var spent: Double = 0.0, var fees: Double = 0.0,
        var pnl: Double = 0.0, var entrySum: Double = 0.0, var leftSum: Int = 0) {
        fun record(t: Trade, win: Boolean, left: Int) {
            trades++; if (win) wins++; spent += t.cost; fees += t.fee
            pnl += t.pnl; entrySum += t.price; leftSum += left
        }
        fun winRate() = if (trades == 0) Double.NaN else 100.0 * wins / trades
        fun roi() = if (spent == 0.0) Double.NaN else 100.0 * pnl / spent
    }
    private val ks = listOf(0.0, 0.25, 0.50, 0.75, 1.00, 1.25, 1.50, 2.00)
    // band=-1 is unrestricted contract price; 0..9 are 1-9c and 10c bins.
    private val stats = ks.flatMap { k -> (-1..9).map { b -> Stat(k, b) } }
        .associateBy { it.k to it.band }
    private var noQuote = 0
    private var badBtc = 0
    private var requestErrors = 0

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        val m = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        m.createNotificationChannel(NotificationChannel(CHANNEL,
            "BTC profit study progress", NotificationManager.IMPORTANCE_LOW))
        m.createNotificationChannel(NotificationChannel(COMPLETE_CHANNEL,
            "BTC profit study complete", NotificationManager.IMPORTANCE_DEFAULT))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            cancel.set(true)
            save("stopping", "Finishing current request, then stopping.")
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        cancel.set(false)
        stats.values.forEach { it.trades=0; it.wins=0; it.spent=0.0; it.fees=0.0;
            it.pnl=0.0; it.entrySum=0.0; it.leftSum=0 }
        noQuote=0; badBtc=0; requestErrors=0
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(ID, notice("Fetching Kalshi historical markets"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID, notice("Fetching Kalshi historical markets"))
        save("running", "Finding recent completed BTC 15-minute markets.", "")
        Thread {
            val wake = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "decisionoverlay:btcProfit")
            try {
                wake.acquire(2L * 60L * 60L * 1000L)
                val output = scan()
                save(if (cancel.get()) "stopped" else "done",
                    if (cancel.get()) "Stopped, partial data below."
                    else "Profit comparison complete; results below.", output)
                if (!cancel.get()) finished("BTC profit study finished")
            } catch (e: Exception) {
                save(if (cancel.get()) "stopped" else "error",
                    "Profit study failed: " + (e.message ?: "unknown").take(170))
                if (!cancel.get()) finished("BTC profit study failed")
            } finally {
                if (wake.isHeld) wake.release()
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }
    private fun save(phase: String, status: String, report: String? = null) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", phase).putString("status", status)
        if (report != null) editor.putString("report", report)
        editor.apply()
    }
    private fun notice(status: String): Notification {
        val p = PendingIntent.getActivity(this, ID,
            Intent(this, BtcAcmBacktestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle("BTC ACM profit backtest")
            .setContentText(status).setContentIntent(p)
            .setOngoing(true).setOnlyAlertOnce(true).build()
    }
    private fun progress(message: String, report: String) {
        save("running", message, report)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ID, notice(message))
    }
    private fun finished(message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        val p = PendingIntent.getActivity(this, ID+1,
            Intent(this, BtcAcmBacktestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(ID+1, NotificationCompat.Builder(this, COMPLETE_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(message)
                .setContentIntent(p).setAutoCancel(true).build())
    }
    private fun json(url: String): JSONObject {
        var error: Exception? = null
        repeat(3) { i ->
            if (cancel.get()) throw IllegalStateException("Canceled")
            try {
                return http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (!r.isSuccessful)
                        throw IllegalStateException("HTTP " + r.code + ": " + body.take(90))
                    JSONObject(body)
                }
            } catch (e: Exception) {
                error = e
                if (i < 2) Thread.sleep((i+1)*700L)
            }
        }
        throw error ?: IllegalStateException("No response")
    }
    private fun listMarkets(path: String): List<Market> {
        val url = API + path + "?series_ticker=KXBTC15M&limit=1000" +
            (if (path == "/markets") "&status=settled" else "")
        val array = json(url).getJSONArray("markets")
        val result = mutableListOf<Market>()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val outcome = item.optString("result").lowercase(Locale.US)
            if (outcome != "yes" && outcome != "no") continue
            val ticker = item.optString("ticker")
            if (!ticker.startsWith("KXBTC15M-")) continue
            val closing = try { Instant.parse(item.getString("close_time")).toEpochMilli() }
                catch (_: Exception) { continue }
            if (closing >= System.currentTimeMillis() - 5L*MINUTE) continue
            val open = Instant.ofEpochMilli(closing-15L*MINUTE).atZone(zone)
            if (open.hour !in 10..22 || open.minute % 15 != 0 || open.second != 0)
                continue
            result.add(Market(ticker, closing, outcome == "yes"))
        }
        return result
    }
    private fun btcDay(day: LocalDate): Map<Long, BtcAcmMath.Candle> {
        val first = day.atTime(9,0).atZone(zone).toInstant().toEpochMilli()
        val last = day.atTime(23,0).atZone(zone).toInstant().toEpochMilli()-1
        val url = "https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
            "&interval=1m&startTime=$first&endTime=$last&limit=1000"
        var error: Exception? = null
        repeat(3) { i ->
            if (cancel.get()) throw IllegalStateException("Canceled")
            try {
                val text = http.newCall(Request.Builder().url(url).build())
                    .execute().use { r ->
                        val body = r.body?.string().orEmpty()
                        if (!r.isSuccessful) throw IllegalStateException(
                            "Binance.US HTTP " + r.code + ": " + body.take(90))
                        body
                    }
                val data = JSONArray(text)
                val out = HashMap<Long, BtcAcmMath.Candle>()
                for (j in 0 until data.length()) {
                    val a = data.getJSONArray(j)
                    val c = BtcAcmMath.Candle(a.getLong(0),a.getDouble(1),
                        a.getDouble(2),a.getDouble(3),a.getDouble(4))
                    if (c.valid()) out[c.at] = c
                }
                return out
            } catch (e: Exception) {
                error = e
                if (i < 2) Thread.sleep((i+1)*700L)
            }
        }
        throw error ?: IllegalStateException("Binance unavailable")
    }
    private fun field(o: JSONObject?, name: String): Double? {
        if (o == null) return null
        val v = o.optString(name + "_dollars",
            o.optString(name, "")).toDoubleOrNull()
        return v?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
    }
    private fun marketQuotes(m: Market): Map<Long, Quote> {
        val first = (m.closeMs - 15L*MINUTE)/1000
        val end = m.closeMs/1000
        val args = "?start_ts=$first&end_ts=$end&period_interval=1"
        val live = API + "/series/KXBTC15M/markets/" + m.ticker + "/candlesticks" + args
        val old = API + "/historical/markets/" + m.ticker + "/candlesticks" + args
        val result = try { json(live) } catch (_: Exception) { json(old) }
        val data = result.getJSONArray("candlesticks")
        val map = mutableMapOf<Long, Quote>()
        for (i in 0 until data.length()) {
            val item = data.getJSONObject(i)
            val ts = item.optLong("end_period_ts",-1)
            if (ts !in first..end) continue
            map[ts] = Quote(field(item.optJSONObject("yes_ask"),"close"),
                field(item.optJSONObject("yes_bid"),"close"))
        }
        return map
    }
    private fun trade(p: Double, winner: Boolean): Trade? {
        val position = BtcAcmProfitMath.tenDollarPosition(p, winner) ?: return null
        return Trade(p, position.fee, position.cost, position.pnl)
    }
    private fun scan(): String {
        val marketMap = mutableMapOf<String,Market>()
        val warnings = mutableListOf<String>()
        for (path in listOf("/markets","/historical/markets")) {
            try { listMarkets(path).forEach { marketMap[it.ticker] = it } }
            catch (e: Exception) { warnings += path + ": " + (e.message ?: "no response") }
        }
        if (marketMap.isEmpty()) error("Kalshi market listings empty: " + warnings.joinToString("; "))
        val markets = marketMap.values.sortedByDescending { it.closeMs }
        val daily = mutableMapOf<LocalDate, Map<Long, BtcAcmMath.Candle>>()
        var tested = 0; var inspected = 0
        for (market in markets) {
            if (cancel.get() || tested == 100 || inspected == 500) break
            inspected++
            val start = market.closeMs - 15L*MINUTE
            val day = Instant.ofEpochMilli(start).atZone(zone).toLocalDate()
            val bars = try { daily.getOrPut(day) { btcDay(day) } }
                catch (_: Exception) { requestErrors++; continue }
            val target = bars[start-MINUTE]?.close
            if (target == null || target <= 0.0 || (0..14).any {
                    bars[start+it*MINUTE] == null }) { badBtc++; continue }
            val quotes = try { marketQuotes(market) }
                catch (_: Exception) { requestErrors++; continue }
            val entered = mutableSetOf<Pair<Double,Int>>()
            var validQuote = false
            for (elapsed in 7..14) {
                val last = start+(elapsed-1)*MINUTE
                val spot = bars[last]!!.close
                val side = if (spot > target) 1 else if (spot < target) -1 else 0
                if (side == 0) continue
                val acm = BtcAcmMath.averageRange(bars,last,60) ?: continue
                val quote = quotes[(start+elapsed*MINUTE)/1000] ?: continue
                val p = if (side == 1) quote.ask else quote.bid?.let { 1.0-it }
                if (p == null || p < 0.01 || p > 0.99) continue
                validQuote = true
                val winning = (side == 1) == market.yesWins
                val t = trade(p,winning) ?: continue
                val band = (p*10.0+1e-8).toInt().coerceIn(0,9)
                val gap = abs(BtcAcmMath.percentFromTarget(spot,target))
                for (k in ks) {
                    if (gap+1e-10 < k*acm*(15-elapsed)) continue
                    for (b in listOf(-1,band)) {
                        val key = k to b
                        if (entered.add(key))
                            stats.getValue(key).record(t,winning,15-elapsed)
                    }
                }
            }
            if (!validQuote) { noQuote++; continue }
            tested++
            if (tested == 1 || tested % 10 == 0) {
                progress("Compared $tested/100 markets",report(tested,inspected,warnings,false))
            }
        }
        return report(tested,inspected,warnings,!cancel.get())
    }
    private fun pct(p: Double) = if (p.isFinite())
        String.format(Locale.US,"%.1f%%",p) else "N/A"
    private fun cash(v: Double) = String.format(Locale.US,"%+.2f",v)
    private fun priceLabel(b: Int) = if (b == -1) "ANY"
        else if (b == 0) "01-09c"
        else String.format(Locale.US,"%02d-%02dc",b*10,b*10+9)
    private fun report(tested: Int, inspected: Int, warnings: List<String>,
        done: Boolean): String = buildString {
        appendLine("BTC/KALSHI 60-CANDLE ACM — HISTORICAL NET PROFIT TEST")
        appendLine("Status: " + if (done) "COMPLETE" else "PARTIAL/RUNNING")
        appendLine("Markets evaluated: $tested of requested 100; candidates inspected: $inspected")
        appendLine("Market start between 10:00-23:00 New York, DST-aware.")
        appendLine("Binance.US BTCUSDT: only COMPLETED 1m candles for ACM60 and")
        appendLine("target. Threshold = ACM60 x MINUTES LEFT x K; earliest")
        appendLine("signal from minutes 7-14 elapsed; NO repeated entry per market/strategy.")
        appendLine("Buy YES when above target, NO when below; win judged by")
        appendLine("Kalshi's ACTUAL recorded Yes/No result (not Binance end close).")
        appendLine("Kalshi 1m YES ask at observation, or NO ask = 1 - YES bid.")
        appendLine("Quotes are HISTORICAL INDICATORS, not assured executable fills.")
        appendLine("Stake up to $10 including general taker fee; whole contracts.")
        appendLine("Fee = ceil_to_cent(0.07 x contracts x price x (1-price));")
        appendLine("settlement fee $0; hold to $1 payout on win or $0 on loss.")
        appendLine("K=0 signals any nonzero lead, larger K requires a bigger lead.")
        appendLine("Missing quotes: $noQuote; missing BTC candles: $badBtc;")
        appendLine("failed network requests: $requestErrors; listing warnings: " +
            warnings.size)
        warnings.take(3).forEach { appendLine("SOURCE: " + it.take(140)) }
        appendLine()
        appendLine("=== ALL CONTRACT PRICES (ONE TRADE PER MARKET PER K) ===")
        appendLine("K | SIGNALS | HIT RATE | WIN% | AVG PRICE | FEES | NET $ | ROI%")
        for (k in ks) {
            val s = stats.getValue(k to -1)
            appendLine(String.format(Locale.US,
                "%.2f | %d/%d | %s | %s | %.1fc | %.2f | %s | %s",
                k,s.trades,tested,pct(if(tested>0)100.0*s.trades/tested else 0.0),
                pct(s.winRate()),if(s.trades>0)100*s.entrySum/s.trades else 0.0,
                s.fees,cash(s.pnl),pct(s.roi())))
        }
        appendLine()
        appendLine("=== K × KALSHI PRICE BANDS (EVERY NONEMPTY COMBINATION) ===")
        appendLine("K | PRICE | TRADES | WIN% | NET $ | ROI% | AVG MIN LEFT")
        for (k in ks) for (b in 0..9) {
            val s = stats.getValue(k to b)
            if (s.trades == 0) {
                appendLine(String.format(Locale.US,
                    "%.2f | %s | 0 | N/A | +0.00 | N/A | N/A",
                    k,priceLabel(b)))
            } else {
                appendLine(String.format(Locale.US,
                    "%.2f | %s | %d | %s | %s | %s | %.1f",
                    k,priceLabel(b),s.trades,pct(s.winRate()),cash(s.pnl),
                    pct(s.roi()),s.leftSum.toDouble()/s.trades))
            }
        }
        appendLine()
        appendLine("=== BEST SAMPLE NET PROFIT: MINIMUM 20 ACTUAL TRIGGERS ===")
        val rank = stats.values.filter { it.band >= 0 && it.trades >= 20 }
            .sortedWith(compareByDescending<Stat> { it.pnl }.thenByDescending { it.trades })
        if (rank.isEmpty()) appendLine("Not enough sample signals for a reliable ranking.")
        rank.take(8).forEachIndexed { i,s ->
            appendLine(String.format(Locale.US,
                "%d: K %.2f; %s; %d trades; %s win; net $%s; ROI %s",
                i+1,s.k,priceLabel(s.band),s.trades,pct(s.winRate()),
                cash(s.pnl),pct(s.roi())))
        }
        appendLine()
        appendLine("Best is sample-specific, NOT a forecast; strategy combinations overlap.")
        appendLine("An out-of-sample period is needed to verify any selected rule.")
        appendLine("Minute OHLC quote may be stale, bid/ask depth unavailable:")
        appendLine("fills, slippage and available 10-dollar order size NOT verified.")
        appendLine("Binance.US price differs from CF Benchmarks Kalshi index.")
        appendLine("No real trades placed or guaranteed future returns.")
    }
}
