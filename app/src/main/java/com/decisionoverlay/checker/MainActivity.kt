package com.decisionoverlay.checker

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
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
        findViewById<EditText>(R.id.symbolInput).setText(prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT"))
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
        findViewById<EditText>(R.id.symbolInput).setText(symbol)
        if (!checksStarted) {
            checksStarted = true
            engine.start(symbol)
        } else if (engine.symbol != symbol) {
            engine.updateSymbol(symbol)
        }
        findViewById<Button>(R.id.startCheckButton).text = "MONITORING • RUNNING"
        setStatus("Background monitoring is already active for $symbol.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
    override fun onDestroy() { engine.stop(); screenScope.cancel(); super.onDestroy() }

    private fun startChecks() {
        val symbol = readSymbol() ?: return
        prefs.edit().putString(OverlayService.KEY_SYMBOL, symbol).apply()
        if (!checksStarted) {
            checksStarted = true
            engine.start(symbol)
            findViewById<Button>(R.id.startCheckButton).text = "MONITORING • RUNNING"
        } else {
            if (symbol != engine.symbol) engine.updateSymbol(symbol) else engine.refreshNow()
        }
        setStatus("Fetching 150 one-minute candles to warm up EMA 99; displaying the latest 60. Target resets on each 15-minute candle close.")
        requestAndLaunchService(symbol)
    }

    private fun readSymbol(): String? {
        val symbol = findViewById<EditText>(R.id.symbolInput).text?.toString()?.trim()?.uppercase().orEmpty()
        if (!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))) {
            setStatus("Enter a Binance.US spot symbol, e.g. BTCUSDT.")
            return null
        }
        return symbol
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
        decisionView.text = "CURRENT DECISION: $decision\\n${metrics.earlySignal}"
        decisionView.setTextColor(when (decision) {
            "UP" -> Color.rgb(65, 210, 145)
            "DOWN" -> Color.rgb(255, 95, 95)
            else -> Color.rgb(190, 198, 212)
        })
        findViewById<IndicatorLineChartView>(R.id.priceChart).setPriceSeries(
            "PRICE • EMA 7 / EMA 99 / 15M TARGET",
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
        findViewById<TextView>(R.id.priceValue).text =
            "EMA 7: ${fmtPrice(metrics.ema7Price)}  •  EMA 99: ${fmtPrice(metrics.ema99Price)}\n15-minute target (${formatTime(metrics.targetTime)}): ${fmtPrice(metrics.targetPrice)}"
        findViewById<TextView>(R.id.volumeValue).text =
            "Each box = one 1-minute candle. Green = candle closed up; red = candle closed down. Taller boxes = unusually high total volume."
        findViewById<TextView>(R.id.momentumValue).text =
            "MACD histogram (12, 26, 9). Bars above zero are positive; bars below zero are negative. Brighter bars show momentum strengthening; muted bars show it easing."
        findViewById<TextView>(R.id.reasonText).text =
            "Trend alerts are target-aware: EARLY DOWN TREND only when price is above the 15-minute target, and EARLY UP TREND only when price is below it. The alert looks for a developing MACD histogram slope change with independent confirmation from EMA 7, candles or volume. Signals are early warnings, not guarantees."
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
        prefs.edit().putString(OverlayService.KEY_SYMBOL, symbol).apply()
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_SYMBOL, symbol)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Monitoring $symbol in the background. You will receive a notification for an early target-matched trend signal.")
    }

    private fun setStatus(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }
}
