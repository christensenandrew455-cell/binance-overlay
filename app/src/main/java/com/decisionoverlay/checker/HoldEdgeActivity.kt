package com.decisionoverlay.checker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * Separate leading-side HOLD strategy. No orders are placed. Monitor is active
 * only while this Activity is visible, never a background trading service.
 */
class HoldEdgeActivity : AppCompatActivity() {
    private data class Market(
        val ticker: String, val target: Double, val closesAt: Long,
        val yesAsk: Double?, val noAsk: Double?
    )
    private val coins = HoldProbabilityEngine.coins
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS).build()
    private val handler = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var scanBusy = false
    private var requestBusy = false
    private var report = ""
    private var lastAlertKey: String? = null
    private lateinit var coinSelector: Spinner
    private lateinit var testAllButton: Button
    private lateinit var monitorButton: Button
    private lateinit var stopButton: Button
    private lateinit var verdict: TextView
    private lateinit var status: TextView
    private lateinit var output: TextView

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Showing signals onscreen does not require notification permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationChannel()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 22, 22, 18)
            setBackgroundColor(0xFF10151D.toInt())
        }
        root.addView(label("STAY ABOVE / BELOW TARGET", 22f, true))
        root.addView(label(
            "Secondary strategy. Historical survival probability for EVERY 1m candle " +
                "in a 15m round, by distance above/below target. BTC steps of $50, " +
                "scaled USD bands for smaller coins. Live: compare Kalshi YES/NO asks " +
                "with estimated fees. Only 10 AM–11 PM NY; alerts only in the " +
                "4–5-minute entry window. Read-only; not guaranteed.", 13f))
        coinSelector = Spinner(this)
        coinSelector.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, coins)
        root.addView(coinSelector)
        testAllButton = Button(this).apply {
            text = "TEST ALL NINE — EVERY CANDLE"
            setOnClickListener { testAllNine() }
        }
        monitorButton = Button(this).apply {
            text = "START LIVE HOLD CHECK"
            setOnClickListener { startMonitoring() }
        }
        stopButton = Button(this).apply {
            text = "STOP MONITORING"
            isEnabled = false
            setOnClickListener { stopMonitoring() }
        }
        verdict = label("WAIT", 27f, true)
        status = label("Choose a coin for live comparison or test all nine.", 13f)
        output = label("", 12f)
        root.addView(testAllButton)
        root.addView(monitorButton)
        root.addView(stopButton)
        root.addView(verdict)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "COPY ALL RESULTS"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Hold probability report", report))
            }
        })
        root.addView(ScrollView(this).apply {
            addView(output)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun startMonitoring() {
        if (scanBusy || monitoring) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        monitoring = true
        lastAlertKey = null
        monitorButton.isEnabled = false
        stopButton.isEnabled = true
        testAllButton.isEnabled = false
        coinSelector.isEnabled = false
        status.text = "Loading or using cached historical probabilities..."
        checkOnce()
    }

    private fun stopMonitoring() {
        monitoring = false
        monitorButton.isEnabled = !scanBusy
        stopButton.isEnabled = false
        testAllButton.isEnabled = !scanBusy
        coinSelector.isEnabled = !scanBusy
        handler.removeCallbacksAndMessages(null)
        status.text = "STOPPED — live monitoring only works while this screen is open."
    }

    override fun onStop() {
        super.onStop()
        if (monitoring) stopMonitoring()
    }
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        client.dispatcher.cancelAll()
    }

    private fun checkOnce() {
        if (!monitoring || requestBusy) return
        val coin = coinSelector.selectedItem.toString()
        requestBusy = true
        Thread {
            var rendered = ""
            var classification = "WAIT"
            var alertMarket: String? = null
            try {
                val historic = HoldProbabilityEngine.load(this, coin) { progress ->
                    runOnUiThread {
                        if (monitoring && !isFinishing) status.text = progress
                    }
                }
                val market = fetchMarket(coin)
                val now = System.currentTimeMillis()
                val remaining = (market.closesAt - now) / 60_000.0
                val spot = fetchSpot(coin)
                val lead = HoldProbabilityEngine.leadingSide(spot, market.target)
                val absoluteDistance = abs(spot - market.target)
                val minute = remaining.toInt().coerceIn(1, 14)
                val band = HoldProbabilityEngine.bandFor(coin, absoluteDistance)
                val key = HoldProbabilityEngine.Key(minute, band, lead)
                val old = historic.rate(key, false)
                val fresh = historic.rate(key, true)
                val ask = when (lead) {
                    1 -> market.yesAsk
                    -1 -> market.noAsk
                    else -> null
                }
                val eval = if (lead == 0)
                    HoldProbabilityEngine.Verdict(
                        "WAIT", "Binance spot exactly at target; no leading side.",
                        Double.NaN, 0.0)
                else HoldProbabilityEngine.decide(old, fresh, ask, remaining)
                val side = when (lead) {
                    1 -> "YES / UP stays above"
                    -1 -> "NO / DOWN stays below"
                    else -> "TIED"
                }
                classification = if (eval.label == "POTENTIAL BUY" && lead != 0)
                    "POTENTIAL BUY " + if (lead == 1) "YES" else "NO"
                else "WAIT"
                if (classification.startsWith("POTENTIAL BUY"))
                    alertMarket = market.ticker + ":" + lead
                val fee = ask?.let { HoldProbabilityEngine.estimatedFee(it) }
                rendered = buildString {
                    appendLine("LIVE STAY-SIDE EDGE — " + coin)
                    appendLine("Market: " + market.ticker)
                    appendLine(String.format(Locale.US,
                        "Time remaining %.2f min | Binance.US spot $%.8f | target $%.8f",
                        remaining, spot, market.target))
                    appendLine(String.format(Locale.US,
                        "Gap: %+.8f USD (%.4f%%) | lead: %s",
                        spot - market.target,
                        (spot - market.target) / market.target * 100, side))
                    appendLine("Price distance band: " +
                        HoldProbabilityEngine.bandLabel(coin, band))
                    appendLine("Minute slot: " + minute + " minutes remaining.")
                    appendLine(String.format(Locale.US,
                        "Kalshi BUY YES ask: %s | BUY NO ask: %s",
                        formatAsk(market.yesAsk), formatAsk(market.noAsk)))
                    appendLine("Older training: " + formatRate(old))
                    appendLine("Newer held-out validation: " + formatRate(fresh))
                    appendLine(String.format(Locale.US,
                        "Conservative lower confidence bound: %.1f%%",
                        eval.lower95 * 100))
                    if (ask != null && fee != null) {
                        appendLine(String.format(Locale.US,
                            "Current-side ask %.2fc + estimated fee %.2fc = %.2f%% break-even.",
                            ask * 100, fee * 100,
                            HoldProbabilityEngine.breakEven(ask) * 100))
                        appendLine(String.format(Locale.US,
                            "Historical expected net (point estimate): %+.2fc per $1 contract (UNVERIFIED)",
                            (fresh.probability - HoldProbabilityEngine.breakEven(ask)) * 100))
                    }
                    appendLine(classification + ": " + eval.why)
                    appendLine("A BUY requires >=100 matching samples EACH in training and validation,")
                    appendLine("and the 95% lower bound from BOTH periods must beat ask+fee+2c.")
                    appendLine("Signal checks refresh around every 12 sec; only one alert per contract/side.")
                    appendLine("Proxy prices are Binance.US, NOT Kalshi CF settlement index.")
                    appendLine("Fees are estimated; ask liquidity, execution and edge can change.")
                    appendLine("No bets placed. This is a research signal, not certain profit.")
                }
            } catch (e: Exception) {
                rendered = "WAIT — " + coin + " data unavailable: " +
                    (e.message ?: e.javaClass.simpleName) +
                    "\nNo BUY signal issued when any required data is missing."
            }
            val text = rendered
            val result = classification
            val alert = alertMarket
            runOnUiThread {
                requestBusy = false
                if (monitoring && !isFinishing && !isDestroyed) {
                    report = text
                    output.text = text
                    verdict.text = result
                    verdict.setTextColor(
                        if (result.startsWith("POTENTIAL BUY")) 0xFF4ED3A0.toInt()
                        else 0xFFE5EDF5.toInt())
                    status.text = "Live " + coin + " check updated. Next refresh ~12 sec."
                    if (alert != null && result.startsWith("POTENTIAL BUY"))
                        notifyIfNew(alert, result)
                    handler.postDelayed({ checkOnce() }, 12_000L)
                }
            }
        }.start()
    }

    private fun testAllNine() {
        if (scanBusy || monitoring) return
        scanBusy = true
        testAllButton.isEnabled = false
        monitorButton.isEnabled = false
        coinSelector.isEnabled = false
        verdict.text = "TESTING ALL NINE"
        output.text = ""
        Thread {
            val reports = StringBuilder()
            val summary = mutableListOf<String>()
            for ((index, coin) in coins.withIndex()) {
                runOnUiThread {
                    if (!isFinishing) status.text = "Coin " +
                        (index + 1) + "/9: " + coin + " historical scan..."
                }
                try {
                    val h = HoldProbabilityEngine.load(this, coin) { progress ->
                        runOnUiThread {
                            if (!isFinishing) status.text = "Coin " +
                                (index + 1) + "/9: " + progress
                        }
                    }
                    reports.appendLine(h.report())
                    summary.add(coin + ": " + h.trainMarkets + " older + " +
                        h.testMarkets + " newer markets; " +
                        "step " + HoldProbabilityEngine.bandLabel(coin, 0))
                } catch (e: Exception) {
                    summary.add(coin + ": UNAVAILABLE " + (e.message ?: "Unknown error"))
                    reports.appendLine(coin + ": no historical probability due to " +
                        (e.message ?: "unknown error"))
                }
                val partial = "=== NINE-COIN SURVIVAL SUMMARY ===\n" +
                    summary.joinToString("\n") + "\n\n" + reports
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        report = partial
                        output.text = partial
                    }
                }
            }
            val finished = "=== NINE-COIN SURVIVAL SUMMARY ===\n" +
                summary.joinToString("\n") + "\n\n" + reports.toString()
            runOnUiThread {
                scanBusy = false
                testAllButton.isEnabled = true
                monitorButton.isEnabled = true
                coinSelector.isEnabled = true
                if (!isFinishing && !isDestroyed) {
                    report = finished
                    output.text = finished
                    verdict.text = "HISTORY COMPLETE"
                    status.text = "All nine attempted. Select a coin and START LIVE HOLD CHECK."
                }
            }
        }.start()
    }

    private fun fetchMarket(coin: String): Market {
        val url = "https://external-api.kalshi.com/trade-api/v2/markets" +
            "?series_ticker=KX" + coin + "15M&status=open&limit=100"
        val arr = JSONObject(fetch(url)).optJSONArray("markets")
            ?: error("No Kalshi market list")
        val now = System.currentTimeMillis()
        val eligible = (0 until arr.length()).mapNotNull { i ->
            val m = arr.getJSONObject(i)
            val expiration = runCatching {
                Instant.parse(m.getString("close_time")).toEpochMilli()
            }.getOrNull() ?: return@mapNotNull null
            val target = m.optString("floor_strike").toDoubleOrNull() ?: return@mapNotNull null
            if (expiration <= now || expiration > now + 15L * 60_000L ||
                target <= 0 || !TradingHours.allowedMarketStart(expiration - 15L * 60_000L))
                return@mapNotNull null
            Market(m.optString("ticker"), target, expiration,
                validAsk(m.optString("yes_ask_dollars")),
                validAsk(m.optString("no_ask_dollars")))
        }
        return eligible.minByOrNull { it.closesAt }
            ?: error("No active 15m Kalshi contract during 10 AM–11 PM New York time")
    }

    private fun fetchSpot(coin: String): Double {
        val json = JSONObject(fetch(
            "https://api.binance.us/api/v3/ticker/price?symbol=" + coin + "USDT"))
        return json.getString("price").toDouble().also {
            require(it.isFinite() && it > 0) { "Invalid Binance.US spot" }
        }
    }
    private fun fetch(url: String): String =
        client.newCall(Request.Builder().url(url).get().build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) error("HTTP " + it.code + ": " + body.take(100))
            require(body.isNotBlank()) { "Empty price feed" }
            body
        }
    private fun validAsk(v: String): Double? =
        v.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
    private fun formatAsk(ask: Double?): String =
        if (ask == null) "NOT QUOTED"
        else String.format(Locale.US, "%.2fc", ask * 100)
    private fun formatRate(rate: HoldProbabilityEngine.Rate): String =
        if (rate.count <= 0) "0/0 — NOT ENOUGH DATA"
        else String.format(Locale.US,
            "%d/%d held = %.1f%% (95%% low %.1f%%)",
            rate.successes, rate.count, rate.probability * 100,
            rate.lower95() * 100)

    private fun notificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(
                    "stay_side_research", "Stay-side research alerts",
                    NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    private fun notifyIfNew(key: String, message: String) {
        if (lastAlertKey == key) return
        lastAlertKey = key
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, HoldEdgeActivity::class.java)
        val pending = PendingIntent.getActivity(this, 2055, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, "stay_side_research")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(message + " — CHECK KALSHI")
            .setContentText("Historical hold rate exceeds price + fees; verify before buying.")
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(3030, n)
    }

    private fun label(value: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xFFE5EDF5.toInt())
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
}
