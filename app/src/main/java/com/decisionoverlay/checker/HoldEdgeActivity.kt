package com.decisionoverlay.checker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * Independent stay-ahead (not reversal) strategy. Every coin is monitored.
 * No trading orders. Checking only runs while this Activity is foreground.
 */
class HoldEdgeActivity : AppCompatActivity() {
    private data class Market(
        val ticker: String, val target: Double, val closesAt: Long,
        val yesAsk: Double?, val noAsk: Double?
    )
    private data class LiveResult(
        val coin: String, val text: String, val verdict: String,
        val alertKey: String?, val minute: Int,
        val gapPct: Double?, val yesAsk: Double?, val noAsk: Double?,
        val historical: HoldProbabilityEngine.Historical?
    )

    private val coins = HoldProbabilityEngine.coins
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(18, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var monitoring = false
    private var testing = false
    private var requestBusy = false
    private var report = ""
    private val alerted = mutableSetOf<String>()
    private val latest = linkedMapOf<String, LiveResult>()
    private lateinit var coinSelector: Spinner
    private lateinit var chart: HoldThresholdChart
    private lateinit var testButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var verdict: TextView
    private lateinit var status: TextView
    private lateinit var output: TextView

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Onscreen comparison works without permission */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationChannel()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 16)
            setBackgroundColor(Color.rgb(16, 21, 29))
        }
        root.addView(label("STAY ABOVE / BELOW TARGET", 21f, true))
        root.addView(label(
            "Secondary strategy • all nine coins • 15m opening tick + every 1m " +
                "through 1 minute left • price gap in PERCENT, not dollars. " +
                "Narrowing green/amber bands show minimum historically supported gap " +
                "at Kalshi's CURRENT ask. Missing evidence leaves a blank line. " +
                "Only 10 AM–11 PM NY; read-only, no orders placed.", 12f))
        root.addView(label("Coin to display on the percentage chart:", 13f))
        coinSelector = Spinner(this)
        coinSelector.adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item, coins)
        root.addView(coinSelector)
        chart = HoldThresholdChart(this)
        root.addView(chart, LinearLayout.LayoutParams(-1,
            (270 * resources.displayMetrics.density).toInt()))
        testButton = Button(this).apply {
            text = "BACKTEST ALL 9 — EVERY MINUTE"
            setOnClickListener { testAllNine() }
        }
        startButton = Button(this).apply {
            text = "START LIVE CHECK — ALL 9 COINS"
            setOnClickListener { startMonitoring() }
        }
        stopButton = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setOnClickListener { stopMonitoring() }
        }
        verdict = label("WAIT", 25f, true)
        status = label("Run the history or start live checks. First download may take several minutes.", 13f)
        output = label("", 12f)
        root.addView(testButton)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(verdict)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "COPY RESULTS"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Nine-coin hold percentages", report))
            }
        })
        // Keep the chart, controls and potentially long nine-coin report
        // in ONE scroll container, so landscape/short phone screens never
        // collapse the results panel to zero height.
        root.addView(output)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        // Changing the selector updates the focused chart without restarting
        // the nine-coin monitor. It does not alter which coins are checked.
        coinSelector.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            override fun onItemSelected(parent: android.widget.AdapterView<*>?,
                view: android.view.View?, position: Int, id: Long) {
                latest[coins[position]]?.let { showChart(it) }
                    ?: HoldProbabilityEngine.cached(this@HoldEdgeActivity, coins[position])?.let {
                        chart.show(it, null, null)
                    }
            }
        }
    }

    private fun startMonitoring() {
        if (testing || monitoring) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED)
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        monitoring = true
        startButton.isEnabled = false
        testButton.isEnabled = false
        stopButton.isEnabled = true
        status.text = "Loading nine histories (cached when recent), then checking live Kalshi asks..."
        latest.clear()
        checkAllLive()
    }

    private fun stopMonitoring() {
        monitoring = false
        main.removeCallbacksAndMessages(null)
        startButton.isEnabled = !testing
        testButton.isEnabled = !testing
        stopButton.isEnabled = false
        status.text = "Stopped. Live checking runs only with this screen open."
    }

    override fun onStop() {
        super.onStop()
        if (monitoring) stopMonitoring()
    }
    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        http.dispatcher.cancelAll()
    }

    private fun checkAllLive() {
        if (!monitoring || requestBusy) return
        requestBusy = true
        Thread {
            val responses = mutableListOf<LiveResult>()
            for ((index, coin) in coins.withIndex()) {
                if (!monitoring) break
                runOnUiThread {
                    if (monitoring && !isFinishing)
                        status.text = "Live " + (index + 1) + "/9: " + coin
                }
                try {
                    val historic = HoldProbabilityEngine.load(this, coin) { progress ->
                        runOnUiThread {
                            if (monitoring && !isFinishing) status.text = progress
                        }
                    }
                    val market = fetchMarket(coin)
                    val now = System.currentTimeMillis()
                    val remaining = (market.closesAt - now) / 60_000.0
                    require(remaining > 0.0 && remaining <= 15.0) {
                        "No active 15m Kalshi market right now"
                    }
                    val spot = fetchSpot(coin)
                    val side = HoldProbabilityEngine.leadingSide(spot, market.target)
                    val gap = (spot - market.target) / market.target * 100.0
                    val minute = ceil(remaining).toInt().coerceIn(1, 15)
                    val band = HoldProbabilityEngine.bandForPercent(kotlin.math.abs(gap))
                    val key = HoldProbabilityEngine.Key(minute, band, side)
                    val yesAsk = market.yesAsk
                    val noAsk = market.noAsk
                    val ask = if (side == 1) yesAsk else if (side == -1) noAsk else null
                    val old = historic.rate(key, false)
                    val fresh = historic.rate(key, true)
                    val trend = fetchThreeMinuteTrend(coin, now)
                    val aligned = side != 0 && trend == side
                    val trendKey = HoldProbabilityEngine.Key(minute, band, side, 1)
                    val oldTrend = historic.rate(trendKey, false)
                    val freshTrend = historic.rate(trendKey, true)
                    // Trend-specific probabilities are used only with enough
                    // aligned cases in BOTH independent periods. Other cases
                    // fall back to baseline rather than manufacturing samples.
                    val canUseTrend = aligned &&
                        oldTrend.count >= HoldProbabilityEngine.MIN_MATCHES &&
                        freshTrend.count >= HoldProbabilityEngine.MIN_MATCHES
                    val modelTrain = if (canUseTrend) oldTrend else old
                    val modelTest = if (canUseTrend) freshTrend else fresh
                    val evaluation = if (side == 0)
                        HoldProbabilityEngine.Verdict("WAIT",
                            "Spot exactly equals target; no leading side.",
                            Double.NaN, 0.0)
                    else HoldProbabilityEngine.decide(modelTrain, modelTest, ask, remaining)
                    // Do not treat a countertrend spike as a reliable hold.
                    val label = if (evaluation.label == "POTENTIAL BUY" && aligned)
                        "POTENTIAL BUY " + (if (side == 1) "YES" else "NO")
                    else "WAIT"
                    val reason = if (!aligned)
                        "Recent 3m trend is not aligned with leading side."
                    else evaluation.why
                    val threshold = if (side == 0 || ask == null) null
                        else historic.threshold(minute, side, ask,
                            if (canUseTrend) 1 else 0)
                    val msg = buildString {
                        appendLine(coin + " | " + label + " | " +
                            String.format(Locale.US, "%.1fm left", remaining))
                        appendLine(String.format(Locale.US,
                            "Target $%.6f | Binance spot $%.6f | GAP %+.4f%% | %s",
                            market.target, spot, gap,
                            if (side == 1) "YES leads" else if (side == -1)
                                "NO leads" else "TIED"))
                        appendLine("Current percentage band: " +
                            HoldProbabilityEngine.bandLabel(band) +
                            " | minute " + minute + " of 15")
                        appendLine(String.format(Locale.US,
                            "Kalshi YES ask %s | NO ask %s | relevant ask %s",
                            askText(yesAsk), askText(noAsk), askText(ask)))
                        appendLine("Prior 3m trend: " +
                            if (trend == 1) "UP" else if (trend == -1) "DOWN" else "FLAT")
                        appendLine("Trend aligned: " + aligned +
                            " | probability model: " +
                            if (canUseTrend) "trend-confirmed" else "all cases")
                        appendLine("Older: " + rateText(modelTrain) +
                            " | newer held-out: " + rateText(modelTest))
                        if (ask != null) {
                            appendLine(String.format(Locale.US,
                                "Buy price %.2fc + estimated fee %.2fc = %.2f%% break-even",
                                ask * 100, HoldProbabilityEngine.estimatedFee(ask) * 100,
                                HoldProbabilityEngine.breakEven(ask) * 100))
                            if (fresh.count > 0)
                                appendLine(String.format(Locale.US,
                                    "Newer historical hold %.1f%% | conservative %.1f%%",
                                    modelTest.probability * 100,
                                    evaluation.lower95 * 100))
                        }
                        appendLine("Minimum qualifying distance now: " +
                            if (threshold == null) "NOT ESTABLISHED" else
                                String.format(Locale.US, "%.2f%%", threshold))
                        appendLine("Reason: " + reason)
                    }
                    val keyAlert = if (label.startsWith("POTENTIAL BUY"))
                        coin + ":" + market.ticker + ":" + side else null
                    responses.add(LiveResult(coin, msg, label, keyAlert,
                        minute, gap, yesAsk, noAsk, historic))
                } catch (e: Exception) {
                    responses.add(LiveResult(coin,
                        coin + ": WAIT — unavailable: " +
                            (e.message ?: e.javaClass.simpleName), "WAIT",
                        null, 15, null, null, null,
                        HoldProbabilityEngine.cached(this, coin)))
                }
                val snapshot = responses.toList()
                runOnUiThread {
                    if (monitoring && !isFinishing && !isDestroyed) {
                        for (r in snapshot) latest[r.coin] = r
                        displayLiveSnapshot(snapshot)
                    }
                }
            }
            runOnUiThread {
                requestBusy = false
                if (monitoring && !isFinishing && !isDestroyed) {
                    status.text = "All " + responses.size +
                        " coins checked. Next refresh in ~20 seconds."
                    main.postDelayed({ checkAllLive() }, 20_000L)
                }
            }
        }.start()
    }

    private fun displayLiveSnapshot(all: List<LiveResult>) {
        val winners = all.filter { it.verdict.startsWith("POTENTIAL BUY") }
        verdict.text = if (winners.isEmpty()) "WAIT — NO VERIFIED EDGE"
            else winners.joinToString(" | ") { it.coin + " " + it.verdict }
        verdict.setTextColor(if (winners.isEmpty()) Color.LTGRAY
            else Color.rgb(63, 219, 155))
        report = buildString {
            appendLine("LIVE NINE-COIN PERCENTAGE HOLD CHECK")
            appendLine("Local hours 10 AM–11 PM New York. No automatic orders.")
            appendLine("Percentage history uses Binance.US OHLC4, not Kalshi CF settlement.")
            appendLine("Kalshi asks are actual latest quoted prices, not win probabilities.")
            appendLine("One-contract estimated taker fee; spread/slippage not modeled.")
            appendLine()
            for (r in all) { appendLine(r.text); appendLine() }
        }
        output.text = report
        latest[coinSelector.selectedItem.toString()]?.let { showChart(it) }
        for (r in winners) {
            r.alertKey?.let { key ->
                if (alerted.add(key)) notifyPotential(r.coin, r.verdict)
            }
        }
    }

    private fun showChart(result: LiveResult) {
        val hist = result.historical ?: return
        chart.show(hist, result.yesAsk, result.noAsk,
            result.minute, result.gapPct)
    }

    private fun testAllNine() {
        if (testing || monitoring || requestBusy) return
        testing = true
        testButton.isEnabled = false
        startButton.isEnabled = false
        verdict.text = "TESTING 15 TO 1 MINUTES"
        Thread {
            val reports = StringBuilder()
            val summary = mutableListOf<String>()
            for ((i, coin) in coins.withIndex()) {
                runOnUiThread {
                    if (!isFinishing) status.text = "Historical " + (i + 1) + "/9: " + coin
                }
                try {
                    val history = HoldProbabilityEngine.load(this, coin) { detail ->
                        runOnUiThread {
                            if (!isFinishing) status.text = detail
                        }
                    }
                    reports.appendLine(history.report())
                    summary.add(coin + ": " + history.trainMarkets + " training + " +
                        history.testMarkets + " testing, 0.05% increments")
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed &&
                            coinSelector.selectedItem.toString() == coin)
                            chart.show(history, null, null)
                    }
                } catch (e: Exception) {
                    summary.add(coin + ": ERROR " + (e.message ?: "unknown"))
                    reports.appendLine(coin + " unavailable: " +
                        (e.message ?: "unknown"))
                }
                val partial = "=== NINE-COIN PERCENTAGE SURVIVAL SUMMARY ===\n" +
                    summary.joinToString("\n") + "\n\n" + reports.toString()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        report = partial
                        output.text = partial
                    }
                }
            }
            runOnUiThread {
                testing = false
                testButton.isEnabled = true
                startButton.isEnabled = true
                if (!isFinishing && !isDestroyed) {
                    verdict.text = "HISTORY FINISHED"
                    status.text = "Nine coins attempted; see 15m-to-1m threshold table and chart."
                }
            }
        }.start()
    }

    private fun fetchMarket(coin: String): Market {
        val url = "https://external-api.kalshi.com/trade-api/v2/markets" +
            "?series_ticker=KX" + coin + "15M&status=open&limit=100"
        val array = JSONObject(fetch(url)).optJSONArray("markets")
            ?: error("Kalshi returned no markets")
        val now = System.currentTimeMillis()
        val markets = (0 until array.length()).mapNotNull { i ->
            val market = array.getJSONObject(i)
            val closesAt = runCatching {
                Instant.parse(market.getString("close_time")).toEpochMilli()
            }.getOrNull() ?: return@mapNotNull null
            val target = market.optString("floor_strike").toDoubleOrNull()
                ?: return@mapNotNull null
            if (closesAt <= now || closesAt > now + 15 * 60_000L ||
                target <= 0.0 || !TradingHours.allowedMarketStart(
                    closesAt - 15L * 60_000L)) return@mapNotNull null
            Market(market.optString("ticker"), target, closesAt,
                askValue(market.optString("yes_ask_dollars")),
                askValue(market.optString("no_ask_dollars")))
        }
        return markets.minByOrNull { it.closesAt }
            ?: error("No active eligible 15-minute Kalshi contract")
    }

    private fun fetchSpot(coin: String): Double {
        val j = JSONObject(fetch("https://api.binance.us/api/v3/ticker/price?symbol=" +
            coin + "USDT"))
        return j.getDouble("price").also {
            require(it > 0.0 && it.isFinite()) { "Binance spot unavailable" }
        }
    }

    /** Trend uses only CLOSED historical candles; no incomplete last 1m bar. */
    private fun fetchThreeMinuteTrend(coin: String, now: Long): Int {
        val candles = JSONArray(fetch("https://api.binance.us/api/v3/klines?symbol=" +
            coin + "USDT&interval=1m&limit=7"))
        val closed = (0 until candles.length()).map { candles.getJSONArray(it) }
            .filter { it.getLong(6) <= now }
            .sortedBy { it.getLong(0) }
            .takeLast(4)
        if (closed.size < 4) return 0
        val first = closed.first().getDouble(4)
        val last = closed.last().getDouble(4)
        return when { last > first -> 1; last < first -> -1; else -> 0 }
    }

    private fun fetch(url: String): String =
        http.newCall(Request.Builder().url(url).get().build()).execute().use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful)
                error("HTTP " + it.code + " — " + body.take(110))
            require(body.isNotBlank()) { "Empty API response" }
            body
        }

    private fun askValue(s: String): Double? =
        s.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 && it < 1.0 }
    private fun askText(a: Double?): String =
        if (a == null) "NO QUOTE"
        else String.format(Locale.US, "%.2fc", a * 100)
    private fun rateText(r: HoldProbabilityEngine.Rate): String =
        if (r.count == 0) "0/0 N/A"
        else String.format(Locale.US, "%d/%d = %.1f%% (95%% lower %.1f%%)",
            r.successes, r.count, r.probability * 100, r.lower95() * 100)

    private fun notificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel("stay_side_research",
                    "Kalshi stay-side potential opportunities",
                    NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    private fun notifyPotential(coin: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, HoldEdgeActivity::class.java)
        val open = PendingIntent.getActivity(this, 2055, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alert = NotificationCompat.Builder(this, "stay_side_research")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(coin + ": " + text + " (verify quote)")
            .setContentText("Historical % hold estimate beats ask + estimated fee; manual check required.")
            .setContentIntent(open)
            .setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(coin.hashCode(), alert)
    }

    private fun label(s: String, size: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.rgb(229, 237, 245))
            setPadding(0, 8, 0, 8)
            setTextIsSelectable(true)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
}
