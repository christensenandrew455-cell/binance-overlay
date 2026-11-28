package com.decisionoverlay.checker

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-phone Kalshi foreground trader. Requires explicitly imported private
 * credentials and the user's START action. Does NOT depend on Vercel.
 * No orders are submitted until authentication + risk + schedule pass.
 */
class KalshiPhoneTradingService : Service() {
    companion object {
        const val ACTION_START = "com.decisionoverlay.checker.TRADER_START"
        const val ACTION_STOP = "com.decisionoverlay.checker.TRADER_STOP"
        const val ACTION_CONNECT = "com.decisionoverlay.checker.TRADER_CONNECT"
        private const val FGS_ID = 4182
        private val ET = ZoneId.of("America/New_York")
        private val LEFT = setOf(12,10,9,8,7,5)
        private val ZERO = BigDecimal.ZERO
        private val FIFTY = BigDecimal("0.50")
        private val SIXTY = BigDecimal("0.60")
    }

    private val workerAlive = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    private lateinit var ledger: KalshiPhoneLedger
    private lateinit var api: KalshiPhoneApi
    private var lastError: String = ""
    private var cachedMarket: KalshiPhoneApi.Market? = null
    private var cachedAt = 0L
    private var lastReconcile = 0L
    private var lastBalance = 0L
    private var trackers = HashMap<String, QuoteTrigger>()

    private class QuoteTrigger {
        var anchor: Long? = null
        var fired = false
        fun read(ask: BigDecimal?, now: Long): String? {
            if (fired) return null
            if (ask == null || ask <= ZERO || ask >= BigDecimal.ONE) {
                anchor = null
                return null
            }
            if (anchor != null && now-anchor!! > 120_000L) anchor = null
            if (ask <= FIFTY) {
                if (anchor == null) anchor = now
                return null
            }
            if (anchor != null && ask >= SIXTY) {
                fired = true
                return if(ask.compareTo(SIXTY)==0) "BUY" else "MISSED"
            }
            return null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ledger = KalshiPhoneLedger(this)
        api = KalshiPhoneApi(this)
    }

    private fun preparingNotification(): Notification {
        // activeNotification creates notification channels; this is replaced
        // before displaying ACTIVE and is not a trading confirmation.
        TradingNotifications.activeNotification(this)
        return NotificationCompat.Builder(this, "kalshi_trading_running")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Kalshi connection check")
            .setContentText("Checking the signed account balance")
            .setOngoing(true)
            .setOnlyAlertOnce(true).build()
    }

    private fun foreground(note: Notification) {
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(FGS_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(FGS_ID,note)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopped.set(true)
                ledger.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                TradingNotifications.showStopped(this,"Stopped by you")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_CONNECT, ACTION_START, null -> {
                if (workerAlive.getAndSet(true)) return START_STICKY
                stopped.set(false)
                foreground(preparingNotification())
                val command = intent?.action
                Thread({
                    try {
                        if(!KalshiPhoneCredentials.exists(this)) {
                            ledger.log("NO_CREDENTIALS","Import the private Kalshi PEM securely on this phone")
                            return@Thread
                        }
                        val funds = api.balance()
                        ledger.updateFunds(funds)
                        ledger.log("CONNECTED","Verified Kalshi balance ${funds.equityCents} cents")
                        if(command==ACTION_CONNECT) return@Thread
                        if(command==ACTION_START) {
                            val started = ledger.activate(funds)
                            if(started!="ACTIVE") {
                                ledger.log("START_REJECTED",started)
                                return@Thread
                            }
                        }
                        if(ledger.hasUncertainOrders()) {
                            ledger.forceStop("UNKNOWN_ORDER: review Kalshi executions")
                            TradingNotifications.showStopped(this,
                                "Previous order status is uncertain. Review before restarting.")
                            return@Thread
                        }
                        if(!ledger.status().active) return@Thread
                        foreground(TradingNotifications.activeNotification(this))
                        ledger.log("WORKER","Foreground trading loop started")
                        runLoop()
                    } catch(ex: Exception) {
                        ledger.log("CONNECTION_ERROR",ex.javaClass.simpleName+": "+ex.message.orEmpty().take(160))
                    } finally {
                        workerAlive.set(false)
                        // A fully active user session is sticky while worker runs;
                        // on normal termination remove the status notification.
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                },"KalshiForegroundTrader").start()
                return START_STICKY
            }
            else -> return START_NOT_STICKY
        }
    }

    private fun allowed(now: Instant, close: Instant): Boolean {
        val local = now.atZone(ET)
        val hourAllowed = local.hour in 10..11 || local.hour==17
        val remaining = Duration.between(now,close).seconds
        return hourAllowed && remaining > 0 && (remaining/60).toInt() in LEFT
    }
    private fun insideHours(now: Instant): Boolean {
        val h=now.atZone(ET).hour
        return h in 10..11 || h==17
    }

    private fun current(now: Instant): KalshiPhoneApi.Market? {
        val time=SystemClock.elapsedRealtime()
        if (time-cachedAt>12_000L || cachedMarket?.closeTime?.isBefore(now)==true) {
            cachedMarket=api.currentMarket(now)
            cachedAt=time
        }
        return cachedMarket
    }

    private fun runLoop() {
        while (!stopped.get() && ledger.status().active) {
            val begun=SystemClock.elapsedRealtime()
            try {
                val now=Instant.now()
                if(begun-lastBalance>12_000L) {
                    val funds=api.balance()
                    lastBalance=begun
                    ledger.checkRisk(funds)
                    if (!ledger.status().active) {
                        TradingNotifications.showStopped(this, ledger.status().reason)
                        break
                    }
                }
                if(begun-lastReconcile>20_000L) {
                    lastReconcile=begun
                    reconcile()
                }
                if(insideHours(now)) {
                    val market=current(now)
                    if(market!=null && !ledger.hasMarket(market.ticker)) {
                        val keys=market.ticker
                        if(trackers.keys.any { !it.startsWith("$keys:") }) trackers.clear()
                        // Observe both contract ask series, including a few minutes
                        // before the allowed trigger minute, to detect 50 -> 60.
                        for (outcome in listOf("YES","NO")) {
                            if(stopped.get() || !ledger.status().active) break
                            val quote=api.ask(keys,outcome)
                            val tracker=trackers.getOrPut("$keys:$outcome") { QuoteTrigger() }
                            val decision=tracker.read(quote,SystemClock.elapsedRealtime())
                            if(decision=="MISSED") ledger.log("MISSED_LIMIT","market=$keys outcome=$outcome ask=$quote")
                            if(decision=="BUY" && allowed(Instant.now(),market.closeTime)) {
                                ledger.log("BUY_SIGNAL","market=$keys outcome=$outcome ask=$quote")
                                buy(market,outcome)
                                break
                            }
                        }
                    }
                } else trackers.clear()
                lastError=""
            } catch (ex: Exception) {
                trackers.clear()
                val error=ex.javaClass.simpleName+": "+ex.message.orEmpty().take(130)
                if(error!=lastError) ledger.log("WORKER_ERROR",error)
                lastError=error
            }
            val sleep=(3000L-(SystemClock.elapsedRealtime()-begun)).coerceAtLeast(500L)
            try { Thread.sleep(sleep) } catch(_: InterruptedException) { break }
        }
    }

    private fun buy(m: KalshiPhoneApi.Market, outcome: String) {
        if(stopped.get() || !ledger.status().active ||
            !allowed(Instant.now(),m.closeTime) || ledger.hasMarket(m.ticker)) return
        // Fresh equity + cash check, not just the balance when Start was tapped.
        val funds=api.balance()
        val wager=ledger.checkRisk(funds) ?: return
        if(stopped.get()) return
        val quoteAt=SystemClock.elapsedRealtime()
        val ask=api.ask(m.ticker,outcome)
        if(ask==null || ask.compareTo(SIXTY)!=0 ||
            SystemClock.elapsedRealtime()-quoteAt>2000L ||
            !allowed(Instant.now(),m.closeTime) || stopped.get()) {
            ledger.log("MISSED_ENTRY","market=${m.ticker} outcome=$outcome fresh_ask=$ask")
            return
        }
        // Budget includes up to 10c per contract fee reserve. Never exceed $600.
        val count=(minOf(wager,funds.cashCents)/70L).toInt().coerceAtMost(1000)
        if(count<=0) return
        val clientId=api.newClientId()
        if(!ledger.claim(m.ticker,outcome,count,clientId)) return
        try {
            // An order with a network timeout is never resent automatically.
            if(stopped.get() || !ledger.status().active)
                throw IllegalStateException("Stopped after durable order claim")
            val receipt=api.submit(m.ticker,outcome,count,clientId)
            if(receipt.orderId.isNullOrBlank()) error("Unknown order ID from Kalshi")
            ledger.acknowledge(m.ticker,receipt.orderId)
        } catch(ex: Exception) {
            ledger.forceStop("ORDER_UNCERTAIN: review before restarting")
            ledger.log("ORDER_ERROR",ex.javaClass.simpleName)
            TradingNotifications.showStopped(this,"Order uncertain. Check logs and Kalshi.")
        }
    }

    private fun cents(amount: BigDecimal): Long = amount.movePointRight(2)
        .setScale(0,RoundingMode.HALF_UP).longValueExact()

    private fun reconcile() {
        for ((ticker,outcome,orderId) in ledger.pending()) {
            val market=api.market(ticker)
            val state=market.optString("status").lowercase()
            val result=market.optString("result").lowercase()
            if(state !in setOf("settled","finalized") || result !in setOf("yes","no"))
                continue
            try {
                val fills=api.fills(orderId)
                if(fills.isEmpty()) {
                    // Unexpected absence at settlement: do not invent zero fills.
                    ledger.forceStop("MISSING_FILLS: manual review required")
                    return
                }
                var count=BigDecimal.ZERO
                var paid=BigDecimal.ZERO
                var fee=BigDecimal.ZERO
                for (f in fills) {
                    require(f.optString("outcome_side").equals(outcome,ignoreCase=true))
                    require(f.optString("action").equals("buy",ignoreCase=true))
                    val q=BigDecimal(f.getString("count_fp"))
                    val p=BigDecimal(f.getString(if(outcome=="YES")
                        "yes_price_dollars" else "no_price_dollars"))
                    val ffee=BigDecimal(f.getString("fee_cost"))
                    require(q>BigDecimal.ZERO && p>BigDecimal.ZERO && p<BigDecimal.ONE &&
                        ffee>=BigDecimal.ZERO)
                    ledger.log("FILL","market=$ticker fill=${f.optString("fill_id")} " +
                        "contracts=$q price=$p fee=$ffee")
                    count=count.add(q)
                    paid=paid.add(q.multiply(p))
                    fee=fee.add(ffee)
                }
                val returned=if(result==outcome.lowercase()) count else BigDecimal.ZERO
                val pnl=ledger.settle(ticker,cents(paid),cents(fee),cents(returned))
                val abs=BigDecimal(kotlin.math.abs(pnl)).movePointLeft(2)
                    .setScale(2,RoundingMode.UNNECESSARY).toPlainString()
                TradingNotifications.showSettlement(this,(if(pnl>=0) "+" else "-")+abs,
                    ticker.hashCode())
            } catch(ex: Exception) {
                ledger.forceStop("SETTLEMENT_REVIEW: incomplete P&L data")
                ledger.log("RECONCILIATION_ERROR",ex.javaClass.simpleName)
                TradingNotifications.showStopped(this,"Settlement needs manual review")
                return
            }
        }
    }

    override fun onDestroy() {
        stopped.set(true)
        super.onDestroy()
    }
}
