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

        selectedEmaPeriod = prefs.getInt(OverlayService.KEY_EMA_PERIOD, 3).coerceIn(1, 10)
        val cryptoSpinner = findViewById<Spinner>(R.id.cryptoSpinner)
        cryptoSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            assets.map { it.label },
        ).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        setSelectedSymbol(prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT")

        val emaSlider = findViewById<SeekBar>(R.id.emaSlider)
        emaSlider.max = 9
        emaSlider.progress = selectedEmaPeriod - 1
        updateEmaLabel()
        emaSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                selectedEmaPeriod = (progress + 1).coerceIn(1, 10)
                updateEmaLabel()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt(OverlayService.KEY_EMA_PERIOD, selectedEmaPeriod).apply()
                if (checksStarted) engine.updateEmaPeriod(selectedEmaPeriod)
                if (prefs.getBoolean(OverlayService.KEY_MONITORING_ACTIVE, false)) {
                    val symbol = selectedSymbol()
                    val serviceIntent = Intent(this@MainActivity, OverlayService::class.java)
                        .putExtra(OverlayService.EXTRA_SYMBOL, symbol)
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
        selectedEmaPeriod = prefs.getInt(OverlayService.KEY_EMA_PERIOD, 3).coerceIn(1, 10)
        findViewById<SeekBar>(R.id.emaSlider).progress = selectedEmaPeriod - 1
        updateEmaLabel()
        setSelectedSymbol(symbol)
        if (!checksStarted) {
            checksStarted = true
            engine.updateEmaPeriod(selectedEmaPeriod)
            engine.start(symbol)
        } else {
            if (engine.symbol != symbol) engine.updateSymbol(symbol)
            engine.updateEmaPeriod(selectedEmaPeriod)
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
            .apply()
        if (!checksStarted) {
            checksStarted = true
            engine.updateEmaPeriod(selectedEmaPeriod)
            engine.start(symbol)
            findViewById<Button>(R.id.startCheckButton).text = "MONITORING • RUNNING"
        } else {
            if (symbol != engine.symbol) engine.updateSymbol(symbol) else engine.refreshNow()
            engine.updateEmaPeriod(selectedEmaPeriod)
        }
        setStatus("Fetching 150 one-minute candles for $symbol to warm up EMA 99. Price EMA $selectedEmaPeriod is selected. Target resets on each 15-minute candle close.")
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

    private fun updateEmaLabel() {
        findViewById<TextView>(R.id.emaValue).text = "PRICE EMA: $selectedEmaPeriod"
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
            "PRICE • EMA ${metrics.emaPeriod} / EMA 99 / 15M TARGET",
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
        findViewById<TextView>(R.id.orderBookValue).text =
            "5-second weighted average • Bids: ${fmtPrice(averageBids)} USDT • Asks: ${fmtPrice(averageAsks)} USDT"
        findViewById<TextView>(R.id.priceValue).text =
            "EMA ${metrics.emaPeriod}: ${fmtPrice(metrics.emaPrice)}  •  EMA 99: ${fmtPrice(metrics.ema99Price)}\n15-minute target (${formatTime(metrics.targetTime)}): ${fmtPrice(metrics.targetPrice)}"
        findViewById<TextView>(R.id.volumeValue).text =
            "Each box = one 1-minute candle. Green = candle closed up; red = candle closed down. Taller boxes = unusually high total volume."
        findViewById<TextView>(R.id.momentumValue).text =
            "MACD histogram (12, 26, 9). Bars above zero are positive; bars below zero are negative. Brighter bars show momentum strengthening; muted bars show it easing."
        findViewById<TextView>(R.id.reasonText).text =
            "Trend alerts use live price, time remaining, trend-leg size, taker buying/selling volume, and changing near-book liquidity. No final-five-minute wait gate. EMA ${metrics.emaPeriod} is supporting context; EMA 99 remains the longer-term reference. Signals are estimates, not guarantees.\n\n${metrics.signalDetail}"
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
        setStatus("Monitoring $symbol with EMA $selectedEmaPeriod in the background. You will receive a notification for an early target-matched trend signal.")
    }

    private fun setStatus(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }
}
