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
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Read-only live signals while this Activity is visible. No orders are placed. */
class LiveSignalActivity : AppCompatActivity() {
    private data class Market(val ticker: String, val strike: Double, val closesAt: Long)
    private val coins = listOf("BTC", "ETH", "SOL", "XRP", "DOGE", "BNB", "HYPE", "NEAR", "ZEC")
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var inFlight = false
    private var report = ""
    private var lastAlertKey: String? = null
    private var backtestBusy = false
    private lateinit var historyButton: Button
    private lateinit var selector: Spinner
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var verdict: TextView
    private lateinit var status: TextView
    private lateinit var details: TextView

    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Optional permission; the on-screen live check still works. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createNotificationChannel()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFF10151D.toInt())
        }
        fun label(value: String, font: Float, bold: Boolean = false): TextView =
            TextView(this).apply {
                text = value
                textSize = font
                setTextColor(0xFFE5EDF5.toInt())
                setPadding(0, 10, 0, 10)
                setTextIsSelectable(true)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }
        root.addView(label("LIVE TARGET-CROSS SIGNAL", 22f, true))
        root.addView(label(
            "1m entries + 5m direction • EMA4/EMA100 • OHLC/wicks • " +
            "previous highs/lows • volume and momentum • order book • " +
            "executed buy/sell flow. Power % is NOT a win probability.", 13f
        ))
        selector = Spinner(this)
        selector.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, coins)
        root.addView(selector)
        startButton = Button(this).apply {
            text = "START LIVE CHECK (SELECTED COIN)"
            setOnClickListener { startChecking() }
        }
        stopButton = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setOnClickListener { stopChecking() }
        }
        verdict = label("WAIT", 29f, true)
        status = label("Select a coin and press START. Refreshes about every 12 seconds.", 14f)
        details = label("", 13f)
        historyButton = Button(this).apply {
            text = "BACKTEST 100 HISTORICAL SIGNALS"
            setOnClickListener { runHistoricalReplay() }
        }
        root.addView(historyButton)
        root.addView(startButton)
        root.addView(stopButton)
        root.addView(verdict)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "COPY SIGNAL DETAILS"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("Kalshi live signal", report))
            }
        })
        root.addView(ScrollView(this).apply {
            addView(details)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun runHistoricalReplay() {
        if (backtestBusy) return
        stopChecking()
        backtestBusy = true
        historyButton.isEnabled = false
        startButton.isEnabled = false
        selector.isEnabled = false
        val coin = selector.selectedItem.toString()
        verdict.text = "BACKTESTING"
        details.text = ""
        status.text = "Replaying old 15-minute markets for " + coin + "..."
        Thread {
            var output: String? = null
            var failure: String? = null
            try {
                output = HistoricalSignalReplay.run(coin) { progress ->
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) status.text = progress
                    }
                }
            } catch (e: Exception) {
                failure = e.message ?: e.javaClass.simpleName
            }
            val finalOutput = output
            val finalFailure = failure
            runOnUiThread {
                backtestBusy = false
                historyButton.isEnabled = true
                startButton.isEnabled = true
                selector.isEnabled = true
                if (!isFinishing && !isDestroyed) {
                    report = finalOutput ?: "BACKTEST ERROR: " + (finalFailure ?: "Unknown")
                    details.text = report
                    verdict.text = if (finalOutput != null) "BACKTEST FINISHED" else "ERROR"
                    status.text = if (finalOutput != null)
                        "Results ready for " + coin + ". Scroll to view or copy."
                    else "Backtest failed: " + (finalFailure ?: "Unknown")
                }
            }
        }.start()
    }

    private fun startChecking() {
        if (running) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        running = true
        startButton.isEnabled = false
        stopButton.isEnabled = true
        selector.isEnabled = false
        lastAlertKey = null
        status.text = "Fetching fresh " + selector.selectedItem + " market and Binance.US data..."
        runCheck()
    }

    private fun stopChecking() {
        running = false
        startButton.isEnabled = true
        stopButton.isEnabled = false
        selector.isEnabled = true
        main.removeCallbacksAndMessages(null)
        status.text = "STOPPED. Monitoring only runs while this screen is open."
    }

    override fun onStop() {
        super.onStop()
        stopChecking()
    }
    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        http.dispatcher.cancelAll()
    }

    private fun runCheck() {
        if (!running || inFlight) return
        inFlight = true
        val coin = selector.selectedItem.toString()
        Thread {
            var result: SignalEngine.Result? = null
            var error: String? = null
            try {
                val market = fetchMarket(coin)
                val one = fetchBars(coin, "1m")
                val five = fetchBars(coin, "5m")
                val spot = fetchSpot(coin)
                val book = fetchBook(coin)
                val trades = fetchTrades(coin)
                result = SignalEngine.evaluate(SignalEngine.Snapshot(
                    coin = coin, marketTicker = market.ticker,
                    target = market.strike, marketClosesAt = market.closesAt,
                    observedAt = System.currentTimeMillis(), spot = spot,
                    minuteBars = one, fiveMinuteBars = five,
                    bids = book.first, asks = book.second, trades = trades
                ))
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            }
            val finalResult = result
            val finalError = error
            runOnUiThread {
                inFlight = false
                if (running && !isFinishing && !isDestroyed) {
                    if (finalResult != null) {
                        verdict.text = finalResult.verdict
                        verdict.setTextColor(when (finalResult.verdict) {
                            "BUY UP" -> 0xFF47D19B.toInt()
                            "BUY DOWN" -> 0xFFFF8D8D.toInt()
                            else -> 0xFFE5EDF5.toInt()
                        })
                        report = finalResult.report
                        details.text = report
                        status.text = "Updated " +
                            java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date()) +
                            " • " + coin + " • next check ~12s"
                        notifyIfNew(coin, finalResult)
                    } else {
                        verdict.text = "WAIT"
                        verdict.setTextColor(0xFFE5EDF5.toInt())
                        report = coin + ": NO SIGNAL — data error: " + (finalError ?: "unknown")
                        details.text = report
                        status.text = "Data unavailable. Retrying ~12s; no stale signal."
                    }
                    main.postDelayed({ runCheck() }, 12_000L)
                }
            }
        }.start()
    }

    private fun fetchMarket(coin: String): Market {
        val json = json("https://external-api.kalshi.com/trade-api/v2/markets" +
            "?series_ticker=KX" + coin + "15M&status=open&limit=100")
        val markets = json.optJSONArray("markets") ?: error("No Kalshi markets")
        val now = System.currentTimeMillis()
        val available = (0 until markets.length()).mapNotNull { i ->
            val m = markets.getJSONObject(i)
            val close = runCatching {
                Instant.parse(m.getString("close_time")).toEpochMilli()
            }.getOrNull() ?: return@mapNotNull null
            val target = m.optString("floor_strike").toDoubleOrNull() ?: 0.0
            if (target <= 0 || close <= now || close > now + 15 * 60_000L) null
            else Market(m.optString("ticker"), target, close)
        }
        return available.minByOrNull { it.closesAt }
            ?: error("No active Kalshi 15m contract with a usable target")
    }

    private fun fetchBars(coin: String, interval: String): List<SignalEngine.Bar> {
        val candles = array("https://api.binance.us/api/v3/klines?symbol=" +
            coin + "USDT&interval=" + interval + "&limit=180")
        return (0 until candles.length()).mapNotNull { i ->
            val item = candles.getJSONArray(i)
            val o = item.getDouble(1)
            val h = item.getDouble(2)
            val l = item.getDouble(3)
            val c = item.getDouble(4)
            if (o <= 0 || h <= 0 || l <= 0 || c <= 0 || !c.isFinite()) null
            else SignalEngine.Bar(item.getLong(0), item.getLong(6),
                o, h, l, c, item.getDouble(7))
        }
    }

    private fun fetchSpot(coin: String): Double {
        val p = json("https://api.binance.us/api/v3/ticker/price?symbol=" +
            coin + "USDT").getString("price").toDouble()
        require(p > 0 && p.isFinite()) { "Invalid spot ticker" }
        return p
    }

    private fun fetchBook(coin: String): Pair<List<SignalEngine.Level>, List<SignalEngine.Level>> {
        val book = json("https://api.binance.us/api/v3/depth?symbol=" +
            coin + "USDT&limit=50")
        fun parse(name: String): List<SignalEngine.Level> {
            val a = book.getJSONArray(name)
            return (0 until a.length()).mapNotNull { i ->
                val row = a.getJSONArray(i)
                val p = row.getDouble(0)
                val q = row.getDouble(1)
                if (p > 0 && q > 0) SignalEngine.Level(p, q) else null
            }
        }
        return Pair(parse("bids"), parse("asks"))
    }

    private fun fetchTrades(coin: String): List<SignalEngine.Trade> {
        val rows = array("https://api.binance.us/api/v3/aggTrades?symbol=" +
            coin + "USDT&limit=1000")
        return (0 until rows.length()).mapNotNull { i ->
            val t = rows.getJSONObject(i)
            val price = t.getString("p").toDoubleOrNull() ?: 0.0
            val quantity = t.getString("q").toDoubleOrNull() ?: 0.0
            if (price <= 0 || quantity <= 0) null
            else SignalEngine.Trade(price, quantity, t.getLong("T"), t.getBoolean("m"))
        }
    }

    private fun json(url: String): JSONObject = JSONObject(request(url))
    private fun array(url: String): JSONArray = JSONArray(request(url))
    private fun request(url: String): String {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP " + response.code + ": " + body.take(140))
            if (body.isBlank()) error("Empty API response")
            return body
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("kalshi_signal", "Live Kalshi Signals",
                    NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    private fun notifyIfNew(coin: String, result: SignalEngine.Result) {
        if (!result.verdict.startsWith("BUY ")) return
        val key = result.report.substringBefore("\n") + result.verdict
        if (lastAlertKey == key) return
        lastAlertKey = key
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(this, LiveSignalActivity::class.java)
        val pending = PendingIntent.getActivity(this, 1051, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, "kalshi_signal")
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(coin + " • " + result.verdict)
            .setContentText("Signal only. Confirm target, price and remaining time.")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1515, notification)
    }
}
