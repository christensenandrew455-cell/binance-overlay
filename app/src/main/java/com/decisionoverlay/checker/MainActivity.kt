package com.decisionoverlay.checker

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE) }
    private val screenScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MarketEngine
    private var checksStarted = false
    private var pendingSymbol: String? = null
    private var selectedEmaPeriod = 3
    private var selectedOrderBookWindowSeconds = 15

    private data class CryptoAsset(val name: String, val symbol: String) {
        val label: String get() = "$name ($symbol)"
    }

    // Curated major, established assets with USDT spot pairs listed by Binance.US.
    private val assets = listOf(
        CryptoAsset("Bitcoin", "BTCUSDT"),
        CryptoAsset("Ethereum", "ETHUSDT"),
        CryptoAsset("Solana", "SOLUSDT"),
        CryptoAsset("XRP", "XRPUSDT"),
        CryptoAsset("BNB", "BNBUSDT"),
        CryptoAsset("Dogecoin", "DOGEUSDT"),
        CryptoAsset("Cardano", "ADAUSDT"),
        CryptoAsset("Chainlink", "LINKUSDT"),
        CryptoAsset("Avalanche", "AVAXUSDT"),
        CryptoAsset("Litecoin", "LTCUSDT"),
        CryptoAsset("Bitcoin Cash", "BCHUSDT"),
        CryptoAsset("Sui", "SUIUSDT"),
        CryptoAsset("Hedera", "HBARUSDT"),
        CryptoAsset("Polkadot", "DOTUSDT"),
    )

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val symbol = pendingSymbol
        pendingSymbol = null
        if (granted && symbol != null) launchService(symbol)
        else if (symbol != null) setStatus("Notification permission is needed to keep the background monitor running.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)

        selectedEmaPeriod = 3
        selectedOrderBookWindowSeconds = prefs.getInt(OverlayService.KEY_ORDER_BOOK_WINDOW_SECONDS, 15).coerceIn(15, 60)
            .let { if (it <= 15) 15 else if (it <= 30) 30 else if (it <= 45) 45 else 60 }
        val cryptoSpinner = findViewById<Spinner>(R.id.cryptoSpinner)
        cryptoSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            assets.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelectedSymbol(prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT")

        selectedEmaPeriod = 3
        updateEmaLabel()

        val orderBookSlider = findViewById<SeekBar>(R.id.orderBookWindowSlider)
        val orderBookOptions = listOf(15, 30, 45, 60)
        orderBookSlider.max = 3
        orderBookSlider.progress = orderBookOptions.indexOf(selectedOrderBookWindowSeconds).coerceAtLeast(0)
        updateOrderBookWindowLabel()
        orderBookSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedOrderBookWindowSeconds = orderBookOptions[progress.coerceIn(0, 3)]
                updateOrderBookWindowLabel()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(OverlayService.KEY_ORDER_BOOK_WINDOW_SECONDS, selectedOrderBookWindowSeconds).apply()
                if (checksStarted) engine.updateOrderBookWindow(selectedOrderBookWindowSeconds)
                if (prefs.getBoolean(OverlayService.KEY_MONITORING_ACTIVE, false)) {
                    val serviceIntent = Intent(this@MainActivity, OverlayService::class.java)
                        .putExtra(OverlayService.EXTRA_SYMBOL, selectedSymbol())
                        .putExtra(OverlayService.EXTRA_EMA_PERIOD, selectedEmaPeriod)
                    ContextCompat.startForegroundService(this@MainActivity, serviceIntent)
                }
            }
        })

        findViewById<Button>(R.id.startCheckButton).setOnClickListener { startChecks() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            prefs.edit().putBoolean(OverlayService.KEY_MONITORING_ACTIVE, false).apply()
            if (checksStarted) {
                engine.stop()
                checksStarted = false
                findViewById<Button>(R.id.startCheckButton).text = "START MONITORING"
            }
            setStatus("Monitor stopped.")
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            if (checksStarted) engine.refreshNow() else setStatus("Press START MONITORING first.")
        }
        engine = MarketEngine(screenScope, { snapshot -> runOnUiThread { render(snapshot) } })
        engine.updateEmaPeriod(selectedEmaPeriod)
        engine.updateOrderBookWindow(selectedOrderBookWindowSeconds)
        syncWithBackgroundMonitor()
    }

    override fun onResume() {
        super.onResume()
        syncWithBackgroundMonitor()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        syncWithBackgroundMonitor()
    }

    private fun syncWithBackgroundMonitor() {
        val active = prefs.getBoolean(OverlayService.KEY_MONITORING_ACTIVE, false)
        if (!active) return
        val symbol = prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT"
        selectedEmaPeriod = 3
        selectedOrderBookWindowSeconds = prefs.getInt(OverlayService.KEY_ORDER_BOOK_WINDOW_SECONDS, 15).coerceIn(15, 60)
            .let { if (it <= 15) 15 else if (it <= 30) 30 else if (it <= 45) 45 else 60 }
        findViewById<SeekBar>(R.id.orderBookWindowSlider).progress =
            listOf(15, 30, 45, 60).indexOf(selectedOrderBookWindowSeconds).coerceAtLeast(0)
        updateOrderBookWindowLabel()
        updateEmaLabel()
        setSelectedSymbol(symbol)
        if (!checksStarted) {
            checksStarted = true
            engine.updateEmaPeriod(selectedEmaPeriod)
            engine.updateOrderBookWindow(selectedOrderBookWindowSeconds)
            engine.start(symbol)
        } else {
            if (engine.symbol != symbol) engine.updateSymbol(symbol)
            engine.updateEmaPeriod(selectedEmaPeriod)
            engine.updateOrderBookWindow(selectedOrderBookWindowSeconds)
        }
        findViewById<Button>(R.id.startCheckButton).text = "MONITORING • RUNNING"
        setStatus("Background monitoring is already active for $symbol using EMA $selectedEmaPeriod.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
    override fun onDestroy() { engine.stop(); screenScope.cancel(); super.onDestroy() }

    private fun startChecks() {
        val symbol = selectedSymbol()
        prefs.edit()
            .putString(OverlayService.KEY_SYMBOL, symbol)
            .putInt(OverlayService.KEY_EMA_PERIOD, selectedEmaPeriod)
            .putInt(OverlayService.KEY_ORDER_BOOK_WINDOW_SECONDS, selectedOrderBookWindowSeconds)
            .apply()
        if (!checksStarted) {
            checksStarted = true
            engine.updateEmaPeriod(selectedEmaPeriod)
            engine.updateOrderBookWindow(selectedOrderBookWindowSeconds)
            engine.start(symbol)
            findViewById<Button>(R.id.startCheckButton).text = "MONITORING • RUNNING"
        } else {
            if (symbol != engine.symbol) engine.updateSymbol(symbol) else engine.refreshNow()
            engine.updateEmaPeriod(selectedEmaPeriod)
        }
        setStatus("Fetching 150 one-minute and 150 five-minute candles for $symbol. EMA 3/99 are fixed on both timeframes. Target resets on each 15-minute candle close.")
        requestAndLaunchService(symbol)
    }

    private fun selectedSymbol(): String {
        val index = findViewById<Spinner>(R.id.cryptoSpinner).selectedItemPosition
        return assets.getOrElse(index) { assets.first() }.symbol
    }

    private fun setSelectedSymbol(symbol: String) {
        val index = assets.indexOfFirst { it.symbol == symbol }.let { if (it >= 0) it else 0 }
        findViewById<Spinner>(R.id.cryptoSpinner).setSelection(index)
    }

    private fun updateOrderBookWindowLabel() {
        val label = if (selectedOrderBookWindowSeconds == 60) "1 MINUTE" else "$selectedOrderBookWindowSeconds SECONDS"
        findViewById<TextView>(R.id.orderBookWindowValue).text = "ORDER BOOK: $label"
    }

    private fun updateEmaLabel() {
        findViewById<TextView>(R.id.emaValue).text = "FIXED EMA: 3 + EMA 99 (1M & 5M)"
    }

    private fun render(snapshot: EngineSnapshot) {
        val metrics = snapshot.metrics
        findViewById<TextView>(R.id.symbolHeading).text =
            snapshot.symbol + " • " + fmtPrice(metrics.currentPrice)
        val decisionView = findViewById<TextView>(R.id.decisionText)
        val decision = when (metrics.earlySignal) {
            "EARLY UP TREND" -> "UP"
            "EARLY DOWN TREND" -> "DOWN"
            else -> "WAIT"
        }
        decisionView.text = "CURRENT DECISION: $decision\n${metrics.earlySignal}"
        decisionView.setTextColor(when (decision) {
            "UP" -> Color.rgb(65, 210, 145)
            "DOWN" -> Color.rgb(255, 95, 95)
            else -> Color.rgb(190, 198, 212)
        })
        findViewById<IndicatorLineChartView>(R.id.priceChart).setPriceSeries(
            "1M PRICE • EMA 3 / EMA 99 / 15M TARGET",
            snapshot.priceEmaSeries,
            snapshot.priceEma99Series,
            metrics.targetPrice,
        )
        findViewById<IndicatorLineChartView>(R.id.volumeChart).setHeartbeat(
            "VOLUME • GREEN UP / RED DOWN • TALL = HIGH",
            snapshot.volumeSeries,
            snapshot.volumeSpikeSeries,
            snapshot.volumeUpSeries,
        )
        findViewById<IndicatorLineChartView>(R.id.momentumChart).setMomentum(
            "MOMENTUM • MACD HISTOGRAM • GREEN / RED",
            snapshot.momentumSeries,
        )
        findViewById<OrderBookChartView>(R.id.orderBookChart).setSeries(
            snapshot.orderBookBidSeries,
            snapshot.orderBookAskSeries,
        )
        val averageBids = snapshot.orderBookBidSeries.lastOrNull()
        val averageAsks = snapshot.orderBookAskSeries.lastOrNull()
        val windowSeconds = snapshot.orderBookWindowSeconds
        val elapsedSeconds = snapshot.orderBookCollectionElapsedSeconds
        val hasCompletedAverage = snapshot.orderBookBidSeries.isNotEmpty() && snapshot.orderBookAskSeries.isNotEmpty()
        val collectionLabel = if (hasCompletedAverage) {
            "Last completed ${windowSeconds}s batch • collecting next: ${elapsedSeconds}/${windowSeconds}s"
        } else {
            "Collecting first full window: ${elapsedSeconds}/${windowSeconds}s"
        }
        findViewById<TextView>(R.id.orderBookValue).text =
            "${collectionLabel} • Bids: ${fmtPrice(averageBids)} USDT • Asks: ${fmtPrice(averageAsks)} USDT"
        findViewById<TextView>(R.id.priceValue).text =
            "1M EMA 3: ${fmtPrice(metrics.emaPrice)}  •  EMA 99: ${fmtPrice(metrics.ema99Price)}\n5M EMA 3: ${fmtPrice(metrics.fiveMinuteEma3)}  •  EMA 99: ${fmtPrice(metrics.fiveMinuteEma99)} (${metrics.fiveMinuteTrend})\n15-minute target (${formatTime(metrics.targetTime)}): ${fmtPrice(metrics.targetPrice)} • Gap: ${if ((metrics.distanceToTarget ?: 0.0) >= 0.0) "below by" else "above by"} ${fmtPrice(kotlin.math.abs(metrics.distanceToTarget ?: 0.0))}"
        findViewById<TextView>(R.id.volumeValue).text =
            "Each box = one 1-minute candle. Green = candle closed up; red = candle closed down. Taller boxes = unusually high total volume."
        findViewById<TextView>(R.id.momentumValue).text =
            "MACD histogram (12, 26, 9). Bars above zero are positive; bars below zero are negative. Brighter bars show momentum strengthening; muted bars show it easing."
        findViewById<TextView>(R.id.reasonText).text =
            "Trend alerts use live price, time remaining, trend-leg size, taker buying/selling volume, and changing near-book liquidity. No final-five-minute wait gate. EMA 3/99 are calculated separately on 1m and 5m candles. 5m structure confirms 1m entries; RSI, ADX and divergence filter weak setups. Entry requires a pullback/retest and confirmation close. Signals are estimates, not guarantees.\n\n${metrics.signalDetail}"
        findViewById<TextView>(R.id.problemsText).text =
            if (metrics.lastError.isNullOrBlank()) metrics.feedDetail else metrics.feedDetail + "\n" + metrics.lastError
        findViewById<TextView>(R.id.logText).text = snapshot.logText.ifBlank { "Waiting for first Binance.US update…" }
    }

    private fun requestAndLaunchService(symbol: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSymbol = symbol
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchService(symbol)
    }

    private fun launchService(symbol: String) {
        prefs.edit()
            .putString(OverlayService.KEY_SYMBOL, symbol)
            .putInt(OverlayService.KEY_EMA_PERIOD, selectedEmaPeriod)
            .apply()
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_SYMBOL, symbol)
            .putExtra(OverlayService.EXTRA_EMA_PERIOD, selectedEmaPeriod)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Monitoring $symbol with 1m + 5m EMA 3/99 confirmation. You will receive a notification for an early target-matched trend signal.")
    }

    private fun setStatus(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }
}
