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
 * Historical Binance.US BTCUSD collector, launched ONLY by the BACKTEST
 * button. Legacy class name is preserved for Android manifest compatibility.
 * There are NO Kalshi requests or automatic GitHub validation runs.
 */
class KalshiPatternBacktestService : Service() {
    companion object {
        const val PREFS = "btc_touch_target_btcusd_midpoint_v4"
        const val STATUS = "status"
        const val REPORT = "report"
        const val RUNNING = "running"
        const val START = "kalshi.pattern.START"
        const val EDGE = "btc.edge.START"
        const val EXHAUSTIVE = "btc.exhaustive.START"
        const val FOCUSED = "btc.focused.START"
        const val DAILY = "btc.daily.START"
        const val REVERSAL = "btc.reversal.START"
        const val REVERSAL_RESULT = "btc_reversal_csv_v1"
        const val REVERSAL_STATUS = "btc_reversal_status_v1"
        const val DAILY_RESULT = "btc_daily_report_v2"
        const val DAILY_CSV = "btc_daily_csv_v2"
        const val DAILY_STATUS = "btc_daily_status_v2"
        const val FOCUSED_RESULT = "btc_hour_timing_csv_v1"
        const val FOCUSED_STATUS = "btc_hour_timing_status_v1"
        const val EXHAUSTIVE_RESULT = "btc_exhaustive_results_v1"
        const val EXHAUSTIVE_STATUS = "btc_exhaustive_status_v1"
        const val EDGE_RESULT = "indicator_ranking_v1"
        const val INDICATOR_STATUS = "indicator_status_v1"
        private val active = AtomicBoolean(false)
        fun isScanning() = active.get()
        const val STOP = "kalshi.pattern.STOP"
        private const val BASE = "https://api.binance.us/api/v3/klines"
        private const val TARGET_MARKETS = 1000
        private const val MAX_PAGES = 105
        private const val MINUTE_MS = 60_000L
        private const val QUARTER_MS = 15L * MINUTE_MS
        private const val CHANNEL = "binance_27_pattern_backtest"
        private const val NOTIFICATION_ID = 7877
    }

    private var indicatorRun = false
    private var exhaustiveRun = false
    private var focusedRun = false
    private var dailyRun = false
    private var reversalRun = false
    private var dailyConfig = BtcDailyProfitReport.Config()
    private fun statusKey(): String = when {
        reversalRun -> REVERSAL_STATUS
        dailyRun -> DAILY_STATUS
        focusedRun -> FOCUSED_STATUS
        exhaustiveRun -> EXHAUSTIVE_STATUS
        indicatorRun -> INDICATOR_STATUS
        else -> STATUS
    }
    private fun resultKey(): String = when {
        reversalRun -> REVERSAL_RESULT
        dailyRun -> DAILY_RESULT
        focusedRun -> FOCUSED_RESULT
        exhaustiveRun -> EXHAUSTIVE_RESULT
        indicatorRun -> EDGE_RESULT
        else -> REPORT
    }
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
        prefs.edit().putString(statusKey(), message).apply()
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
        indicatorRun = intent?.action == EDGE
        exhaustiveRun = intent?.action == EXHAUSTIVE
        focusedRun = intent?.action == FOCUSED
        dailyRun = intent?.action == DAILY
        reversalRun = intent?.action == REVERSAL
        if (dailyRun) {
            dailyConfig = BtcDailyProfitReport.Config()
            prefs.edit().remove(DAILY_CSV).apply()
        }
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification("Preparing Binance BTC test..."),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(NOTIFICATION_ID, notification("Preparing Binance BTC test..."))
        prefs.edit().putBoolean(RUNNING, true)
            .remove(resultKey()).putString(statusKey(), "Preparing Binance BTC data...")
            .apply()
        Thread {
            try {
                scan(edge = indicatorRun, exhaustive = exhaustiveRun, focused = focusedRun)
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

    private fun getCandles(startTimeMs: Long, endTimeMs: Long): List<BtcAcmMath.Candle> {
        var lastError = ""
        for (attempt in 0..3) {
            if (!active.get()) error("Scan stopped")
            val url = "$BASE?symbol=${BtcAcmMath.BTC_SYMBOL}&interval=1m&limit=1000&startTime=$startTimeMs&endTime=$endTimeMs"
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
                        row.getDouble(3), row.getDouble(4), row.optDouble(5))
                    if (candle.valid() && candle.at in startTimeMs..endTimeMs)
                        candles.add(candle)
                }
                return candles
            }
            if (response.first != 429 && response.first !in 500..599) break
            Thread.sleep(1500L * (attempt + 1))
        }
        error(lastError.ifBlank { "Binance.US candle download failed" })
    }


    // Split saturated responses by timestamp instead of silently accepting a
    // truncated 1,000-trade page. No interpolation of missing seconds.
    private fun getSecondTrades(start: Long, end: Long): List<BtcSecondWarningReport.Trade>? {
        var requests=0
        fun fetch(a: Long,b: Long): List<BtcSecondWarningReport.Trade>? {
            if(!active.get()) error("Scan stopped")
            if(++requests>64) return null
            Thread.sleep(100L)
            val url="https://api.binance.us/api/v3/aggTrades?symbol="+BtcAcmMath.BTC_SYMBOL+
                "&limit=1000&startTime="+a+"&endTime="+b
            val arr=client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val body=response.body?.string().orEmpty()
                if(!response.isSuccessful) error("Historical trades HTTP "+response.code+": "+body.take(120))
                JSONArray(body)
            }
            if(arr.length()>=1000) {
                if(a==b) return null
                val mid=a+(b-a)/2
                val left=fetch(a,mid) ?: return null
                val right=fetch(mid+1,b) ?: return null
                return left+right
            }
            val trades=ArrayList<BtcSecondWarningReport.Trade>(arr.length())
            for(i in 0 until arr.length()) {
                val item=arr.getJSONObject(i)
                val t=BtcSecondWarningReport.Trade(item.getLong("a"),item.getLong("T"),
                    item.getDouble("p"),item.getDouble("q"))
                if(t.at !in a..b || !t.price.isFinite() || t.price<=0 ||
                    !t.qty.isFinite() || t.qty<=0) return null
                trades.add(t)
            }
            if(trades.map { it.id }.distinct().size!=trades.size) return null
            return trades
        }
        return fetch(start,end)
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
        val target = BtcAcmMath.quarterTargetCandle(candles, start)?.midpoint ?: return null
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
            series.add(BinanceTwentySevenPatternReport.Observation(minute, signed, candle.low, candle.high))
        }
        return BinanceTwentySevenPatternReport.Market(
            start, target, finalBtc, series)
    }

    private fun scan(edge: Boolean, exhaustive: Boolean, focused: Boolean) {
        val limit = if (focused) Int.MAX_VALUE else TARGET_MARKETS
        status(if (focused) "Downloading 100 days of Binance BTC 1-minute candles..." else "Downloading Binance BTC 1-minute candles for $limit markets...")
        val zone = java.time.ZoneId.of("America/New_York")
        val now = java.time.ZonedDateTime.now(zone)
        val lastClose = now.toInstant().toEpochMilli() / QUARTER_MS * QUARTER_MS
        val selected = LinkedHashMap<Long, BinanceTwentySevenPatternReport.Market>()
        val examples = mutableMapOf<Long, BtcEdgeTest.Market>()
        val reversalExamples = mutableMapOf<Long, BtcReversalTimingReport.Market>()
        val warmup = if (edge) 345L else 60L
        var pageCount = 0
        for (page in 1..(if (focused) 100 else MAX_PAGES)) {
            if (!active.get()) error("Scan stopped")
            val day = now.toLocalDate().minusDays(page.toLong() - 1)
            val open = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            val close = minOf(lastClose, day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli())
            // The first hour supplies the rolling warmup from this session.
            val fetchStart = if (focused || dailyRun || reversalRun) open - warmup * MINUTE_MS else open
            val firstMarket = fetchStart + warmup * MINUTE_MS
            if (close - firstMarket < QUARTER_MS) continue
            val candles = getCandles(fetchStart, close - 1).associateBy { it.at }
            pageCount++
            for (start in (close - QUARTER_MS) downTo firstMarket step QUARTER_MS) {
                val market = buildMarket(start, candles) ?: continue
                if (edge) examples[start] = BtcEdgeTest.market(start, market.targetBtc, market.upWon, candles, market.minutes.map { it.signedChartLevel }) ?: continue
                if (reversalRun) reversalExamples[start] = BtcReversalTimingReport.market(market, candles)
                selected[start] = market
                if (!focused && selected.size == TARGET_MARKETS) break
            }
            val collected = selected.size
            if (!focused && collected >= limit) break
            status("Binance BTC historical pages: $page; complete 15m " +
                "markets: $collected/$limit")
            try { Thread.sleep(140L) } catch (_: InterruptedException) { break }
        }
        if (selected.isEmpty())
            error("No complete Binance BTC 15-minute markets found. " +
                "Check Binance.US connectivity.")

        val chosen = selected.values.sortedByDescending { it.startMs }
            .take(if (focused) Int.MAX_VALUE else TARGET_MARKETS).sortedBy { it.startMs }
        if (focused) {
            status("Analyzing 50-to-60 one-minute signals by Eastern hour and minute...")
            val csv = BtcHourTimingReport.report(chosen)
            check(active.get()) { "Scan stopped" }
            prefs.edit().putString(FOCUSED_RESULT, csv).putString(FOCUSED_STATUS,
                "Complete: 100 calendar days scanned; ${chosen.size} usable markets; hourly and minute-level signal frequency and settlement accuracy.").apply()
            return
        }
        if (reversalRun) {
            status("Analyzing fixed-direction reversals and existing alert timing...")
            val examplesForReport=chosen.map { reversalExamples.getValue(it.startMs) }
            val minuteCsv=BtcReversalTimingReport.report(examplesForReport)
            check(active.get()) { "Scan stopped" }
            prefs.edit().putString(REVERSAL_RESULT,minuteCsv).apply()
            // Fetch contiguous history across sessions for native-aligned EMA candles.
            // Separate from market selection, so the existing tests keep the same sample.
            var emaStatus = "EMA slope grid included"
            val emaCsv = try {
                val history=HashMap<Long,BtcAcmMath.Candle>()
                val from=chosen.first().startMs-BtcEmaSlopeReport.WARMUP_MINUTES*MINUTE_MS
                val until=chosen.last().startMs
                var cursor=from
                while(cursor<until) {
                    check(active.get()) { "Scan stopped" }
                    val end=minOf(until,cursor+1000L*MINUTE_MS)
                    getCandles(cursor,end-1).forEach { history[it.at]=it }
                    cursor=end
                    status("EMA history: "+history.size+" completed 1m candles")
                    if(cursor<until) Thread.sleep(140L)
                }
                status("Ranking 900 EMA slope configurations...")
                BtcEmaSlopeReport.report(chosen,history)
            } catch(e: Exception) {
                check(active.get()) { "Scan stopped" }
                emaStatus = "EMA grid INCOMPLETE: "+(e.message ?: e.javaClass.simpleName)
                ""
            }
            val csv=minuteCsv+emaCsv
            check(active.get()) { "Scan stopped" }
            // Preserve both minute reports if exchange blocks the expensive tick replay.
            prefs.edit().putString(REVERSAL_RESULT,csv).apply()
            status("Downloading historical BTC trades for 15/30-second warnings...")
            val secondReport=try {
                BtcSecondWarningReport.report(examplesForReport,::getSecondTrades) { done,total ->
                    if(done%25==0 || done==total)
                        status("15/30-second historical trade replay: "+done+"/"+total+" markets")
                }
            } catch(e: Exception) {
                status("Minute report saved; "+emaStatus+"; 15/30-second replay INCOMPLETE: "+(e.message ?: e.javaClass.simpleName))
                return
            }
            check(active.get()) { "Scan stopped" }
            prefs.edit().putString(REVERSAL_RESULT,csv+secondReport).putString(REVERSAL_STATUS,
                (if (chosen.size == TARGET_MARKETS && emaCsv.isNotEmpty()) "COMPLETE" else "INCOMPLETE") +
                    ": "+chosen.size+"/1000 markets; "+emaStatus+"; 15/30-second replay included. Check data coverage in CSV. Binance index only.").apply()
            return
        }
        if (dailyRun) {
            status("Replaying existing buy alerts and grouping daily P&L...")
            val result = BtcDailyProfitReport.analyze(chosen, dailyConfig)
            check(active.get()) { "Scan stopped" }
            prefs.edit().putString(DAILY_RESULT, result.report())
                .putString(DAILY_CSV, result.csv())
                .putString(DAILY_STATUS,
                    (if (chosen.size == TARGET_MARKETS) "COMPLETE" else "INCOMPLETE") +
                        ": " + chosen.size + "/1000 BTC markets · daily simulation")
                .apply()
            return
        }
        if (exhaustive) {
            status("Testing all 1,400 signed two-reading patterns...")
            val result = BtcExhaustivePatternReport.report(chosen)
            if (!active.get()) error("Scan stopped")
            prefs.edit().putString(EXHAUSTIVE_RESULT, result)
                .putString(EXHAUSTIVE_STATUS,
                    (if (chosen.size == TARGET_MARKETS) "COMPLETE" else "INCOMPLETE") +
                    ": " + chosen.size + "/1000 BTC markets · 1,400 patterns scanned")
                .apply()
            return
        }
        if (edge) {
            require(chosen.size == TARGET_MARKETS) { "Only ${chosen.size}/1000 usable markets. A complete test needs 1,000." }
            status("Ranking indicator setups across all 1,000 markets...")
            val result = BtcEdgeTest.report(chosen.map { examples.getValue(it.startMs) })
            if (!active.get()) error("Scan stopped")
            prefs.edit().putString(EDGE_RESULT, result).putString(INDICATOR_STATUS, "Test complete · 1,000 markets").apply()
            return
        }
        val report = BtcSequenceOutcomes.report(chosen)
        val complete = chosen.size == TARGET_MARKETS
        val summary = if (complete)
            "COMPLETE: $TARGET_MARKETS Binance BTC 15-minute markets; sequence outcomes ready to export."
        else
            "INCOMPLETE: ${chosen.size}/$TARGET_MARKETS Binance BTC sections after " +
                "$pageCount download pages. Results shown provisionally."
        prefs.edit().putString(REPORT, report).putString("touch_summary", BtcSequenceOutcomes.summary(report)).putString(STATUS, summary).apply()
    }

    override fun onDestroy() {
        active.set(false)
        super.onDestroy()
    }
}
