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
                val model = BtcAcmOddsHistory.load(this,
                    { message -> save("starting", message) }, { canceled.get() })
                save("running", "BTC odds ready. Watching Kalshi live asks.")
                while (!canceled.get()) {
                    if (!lock.isHeld) lock.acquire(60L * 60L * 1000L)
                    try {
                        checkLive(model)
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

    private fun notifyBuy(
        market: Market, decision: BtcAcmOdds.Decision,
        position: BtcAcmProfitMath.Position
    ) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString("last_alerted_ticker", "") == market.ticker) return
        val open = PendingIntent.getActivity(this, BUY_ID,
            Intent(this, BtcAcmLiveActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val lower = decision.selected?.conservative ?: return
        val body = String.format(Locale.US,
            "Kalshi %s %.0fc | conservative BTC proxy %.1f%% | break-even %.1f%% | " +
                "BTC $%,.2f / target $%,.2f | fee $%.2f | %.1fm left. RECHECK QUOTE.",
            decision.label, (decision.ask ?: 0.0)*100.0,
            lower*100.0,decision.breakEven*100.0,
            decision.spot,market.target,position.fee,
            decision.secondsLeft/60.0)
        val item = NotificationCompat.Builder(this, BUY_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BUY " + decision.label + " — BTC MODEL > KALSHI ASK [TEST]")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body +
                "\nExperimental, historical Binance.US proxy only. " +
                "Kalshi strike/index may differ; do not assume this probability is exact."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(open).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(BUY_ID, item)
        prefs.edit().putString("last_alerted_ticker",market.ticker).apply()
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

    /** Derive ask-side depth from the OPPOSITE bids in Kalshi's order book.
     * YES ask = 1 - best NO bid; NO ask = 1 - best YES bid.
     * Only accept depth at a price consistent with the market quote.
     * The public order-book endpoint can require authorization; if missing,
     * return null and do NOT assert that the full $10 order is executable.
     */
    private fun orderBookAskSize(ticker: String, side: String, askPrice: Double?): Double? {
        if (askPrice == null || side !in listOf("YES", "NO")) return null
        val book = JSONObject(fetch(
            "https://external-api.kalshi.com/trade-api/v2/markets/" +
                ticker + "/orderbook"))
        val levels = book.optJSONObject("orderbook_fp") ?: return null
        val name = if (side == "YES") "no_dollars" else "yes_dollars"
        val bids = levels.optJSONArray(name) ?: return null
        var best = -1.0
        var bestSize: Double? = null
        for (i in 0 until bids.length()) {
            val level = bids.optJSONArray(i) ?: continue
            if (level.length() < 2) continue
            val bid = level.optString(0).toDoubleOrNull() ?: continue
            val quantity = level.optString(1).toDoubleOrNull() ?: continue
            if (bid in 0.01..0.99 && quantity >= 0.0 && bid > best) {
                best = bid
                bestSize = quantity
            }
        }
        if (best < 0.0 || kotlin.math.abs(1.0 - best - askPrice) > 0.0051)
            return null
        return bestSize
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

    private fun checkLive(model: BtcAcmOdds.Model) {
        val checkedAt = System.currentTimeMillis()
        val market = liveMarket(checkedAt)
        val history = history(System.currentTimeMillis())
        val spot = JSONObject(fetch(
            "https://api.binance.us/api/v3/ticker/price?symbol=BTCUSDT"))
            .getString("price").toDouble()
        require(spot.isFinite() && spot > 0.0) { "Invalid live BTC price" }
        val now = System.currentTimeMillis()
        val secondsLeft = (market.closeMs-now)/1000.0
        val decision = BtcAcmOdds.decide(model, spot,market.target,
            history.acmPct,secondsLeft,market.yesAsk,market.noAsk)
        val position = decision.ask?.let { BtcAcmProfitMath.tenDollarPosition(it,true) }
        val listedSize = when (decision.side) {
            1 -> market.yesAskSize
            -1 -> market.noAskSize
            else -> null
        }
        val size = if (!decision.qualified || listedSize != null) listedSize
            else runCatching {
                orderBookAskSize(market.ticker,decision.label,decision.ask)
            }.getOrNull()
        val hasDepth = position != null && size != null &&
            size+1e-8 >= position.contracts
        val buy = decision.qualified && hasDepth &&
            secondsLeft>60.0 && !canceled.get()
        if (buy && position != null) notifyBuy(market,decision,position)

        // Exactly 15 minutes on the horizontal axis:
        // START = target; past completed 1m closes = plain price line;
        // latest point = live ticker; future = no fabricated BTC prices.
        // Two BUY boundaries recalculate with ACM/time/side-specific Kalshi ASK.
        val marketStart=market.closeMs-15L*MINUTE
        val points=JSONArray()
        fun point(at: Long,acm: Double,btc: Double?,future: Boolean) {
            val sleft=(market.closeMs-at)/1000.0
            val minute=BtcAcmOdds.bucket(sleft)
            val upE=model.minimumRatioForAsk(minute,1,market.yesAsk)
            val downE=model.minimumRatioForAsk(minute,-1,market.noAsk)
            fun threshold(e: BtcAcmOdds.Evidence?,direction: Int): Double? {
                if(e==null || !e.valid || !acm.isFinite() || acm<=0) return null
                val delta=e.ratio*acm*(sleft/60.0)/100.0
                return market.target*(1.0+direction*delta)
            }
            val up = if(at==market.closeMs)market.target else threshold(upE,1)
            val down = if(at==market.closeMs)market.target else threshold(downE,-1)
            points.put(JSONArray().apply {
                put(at)
                if(up==null)put(JSONObject.NULL)else put(up)
                if(down==null)put(JSONObject.NULL)else put(down)
                if(btc==null)put(JSONObject.NULL)else put(btc)
                put(future)
            })
        }
        val priorACM=BtcAcmMath.averageRange(
            history.allCandles,marketStart-MINUTE,60) ?: history.acmPct
        // The visual price series literally begins on the Kalshi target.
        point(marketStart,priorACM,market.target,false)
        var t=marketStart+MINUTE
        val lastFinishedOpen=history.lastMinute
        while(t<=lastFinishedOpen+MINUTE && t<now && t<market.closeMs) {
            val price=history.allCandles[t-MINUTE]
            val acm=BtcAcmMath.averageRange(history.allCandles,t-MINUTE,60)
            if(price!=null && acm!=null)
                point(t,acm,price.close,false)
            t+=MINUTE
        }
        point(now,history.acmPct,spot,false)
        var future=now+10_000L
        while(future<market.closeMs){
            point(future,history.acmPct,null,true)
            future+=10_000L
        }
        point(market.closeMs,history.acmPct,null,true)

        fun evidenceDetails(side: Int,ask: Double?): String {
            val threshold=model.minimumRatioForAsk(decision.minute,side,ask)
            if(threshold==null)return "NO SUPPORTED BUY ZONE"
            val need=threshold.ratio*history.acmPct*(secondsLeft/60.0)
            val price=market.target*(1.0+side*need/100.0)
            return String.format(Locale.US,
                "BTC threshold $%,.2f | model floor %.1f%% | train %d, heldout %d",
                price,threshold.conservative*100.0,
                threshold.train.n,threshold.holdout.n)
        }

        val status=when {
            decision.qualified && !hasDepth ->
                "WAIT: Kalshi quote matches model but available ask depth unverified"
            buy -> "BUY " + decision.label + " — check Kalshi ASK NOW"
            else -> decision.reason
        }
        val report=buildString {
            appendLine("BTC / KALSHI — LIVE FAIR-VALUE BUY ZONE")
            appendLine("Market: "+market.ticker)
            appendLine(String.format(Locale.US,
                "BTC $%,.2f | TARGET $%,.2f | %s | %.1f minutes left",
                spot,market.target,decision.label,secondsLeft/60.0))
            appendLine(String.format(Locale.US,
                "ACM60 %.5f%% (last 60 COMPLETED one-minute candle ranges)",
                history.acmPct))
            appendLine(String.format(Locale.US,
                "Relative distance / (ACM60 x time left): %.3f",
                decision.ratio))
            appendLine("BUY boundaries shift as the seconds pass; no fixed 60-69c price filter.")
            appendLine("Kalshi asks: YES "+cents(market.yesAsk)+" | NO "+
                cents(market.noAsk))
            appendLine("YES BUY BOUNDARY: "+evidenceDetails(1,market.yesAsk))
            appendLine("NO BUY BOUNDARY: "+evidenceDetails(-1,market.noAsk))
            if(decision.selected!=null) appendLine(String.format(Locale.US,
                "MODEL: heldout %.1f%% | conservative train/holdout lower %.1f%% " +
                "| independent markets %d/%d",
                decision.selected.holdout.rate*100.0,
                decision.selected.conservative*100.0,
                decision.selected.train.n,decision.selected.holdout.n))
            if(decision.ask!=null && position!=null) {
                appendLine(String.format(Locale.US,
                    "KALSHI %s ask %.1fc | fee-adjusted breakeven %.1f%% " +
                    "| conservative edge %+.1f percentage points",
                    decision.label,decision.ask*100.0,
                    decision.breakEven*100.0,decision.margin*100.0))
                appendLine(String.format(Locale.US,
                    "$10 budget: %d contracts, total $%.2f incl fee $%.2f",
                    position.contracts,position.cost,position.fee))
                appendLine("Ask depth: "+(size?.toString() ?: "UNAVAILABLE") +
                    " contracts (must be >= "+position.contracts+")")
            }
            appendLine("DECISION: "+status)
            appendLine("Chart: LEFT is market START, RIGHT is expiration.")
            appendLine("Single line shows actual BTC prices within the current 15m market only.")
            appendLine("Green/Red curves = dynamic fee-aware YES/NO buy boundaries.")
            appendLine("Curves use CURRENT Kalshi asks, not historical Kalshi quotes.")
            appendLine("Dashed future uses CURRENT ACM as an illustration, not a price forecast.")
            appendLine("Calibrated with "+model.trainMarkets+" older markets and "+
                model.testMarkets+" held-out newer BTC proxy markets.")
            appendLine("Binance.US is NOT Kalshi's settlement index. No trade placed.")
        }
        val upper=model.minimumRatioForAsk(decision.minute,1,market.yesAsk)
            ?.let{market.target*(1.0+it.ratio*history.acmPct*
                secondsLeft/60.0/100.0)} ?: market.target
        val lower=model.minimumRatioForAsk(decision.minute,-1,market.noAsk)
            ?.let{market.target*(1.0-it.ratio*history.acmPct*
                secondsLeft/60.0/100.0)} ?: market.target
        save("running",status,report,history,market.target,
            points.toString(),upper,lower,spot)
    }

    private fun cents(value: Double?): String =
        if (value == null) "UNAVAILABLE"
        else String.format(Locale.US, "%.2f¢", value * 100.0)
}
