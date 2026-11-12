package com.decisionoverlay.checker

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private var pendingShowOverlay = false

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val symbol = pendingSymbol
        pendingSymbol = null
        if (granted && symbol != null) launchService(symbol, pendingShowOverlay)
        else if (symbol != null) setStatus("Notification permission is needed to keep the background monitor running.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        setContentView(R.layout.activity_main)
        findViewById<EditText>(R.id.symbolInput).setText(prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT"))
        findViewById<Button>(R.id.startCheckButton).setOnClickListener { startChecks() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
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
        requestAndLaunchService(symbol, false)
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
        findViewById<TextView>(R.id.outlierText).text =
            "OVERALL TREND: ${metrics.trend}  •  Showing ${metrics.displayedCandleCount} latest candles"
        findViewById<TextView>(R.id.overallStatus).text = when {
            metrics.candleCount < 100 -> "WARMING UP EMA 99"
            else -> "OVERALL TREND: ${metrics.trend}"
        }
        findViewById<TextView>(R.id.reasonText).text =
            "EMA 7 and EMA 99 are calculated locally from candle closes. The target is the close of the last completed 1-minute candle ending at :00, :15, :30 or :45; it stays unchanged until the next checkpoint. Volume boxes are colored by candle direction (green close above open, red close below open); box height highlights unusually high total traded volume, not buyer-versus-seller volume. Momentum uses the MACD (12, 26, 9) histogram, shown as bars around zero; green is positive and red is negative, with brighter shades showing momentum strengthening. The yellow and purple signal lines are hidden."
        findViewById<TextView>(R.id.problemsText).text =
            if (metrics.lastError.isNullOrBlank()) metrics.feedDetail else metrics.feedDetail + "\n" + metrics.lastError
        findViewById<TextView>(R.id.logText).text = snapshot.logText.ifBlank { "Waiting for first Binance.US update…" }
    }

    private fun startOverlay() {
        val symbol = readSymbol() ?: return
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Android needs permission for the floating monitor. Opening permission settings.")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        requestAndLaunchService(symbol, true)
    }

    private fun requestAndLaunchService(symbol: String, showOverlay: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSymbol = symbol
            pendingShowOverlay = showOverlay
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        launchService(symbol, showOverlay)
    }

    private fun launchService(symbol: String, showOverlay: Boolean) {
        prefs.edit().putString(OverlayService.KEY_SYMBOL, symbol).apply()
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_SYMBOL, symbol)
            .putExtra(OverlayService.EXTRA_SHOW_PANEL, showOverlay)
        ContextCompat.startForegroundService(this, intent)
        setStatus(if (showOverlay) "Floating monitor running for $symbol (EMA 7 / EMA 99, 15-minute target, volume spikes, colored momentum)."
        else "Monitor running for $symbol (EMA 7 / EMA 99, 15-minute target, volume spikes, colored momentum).")
    }

    private fun setStatus(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }
}
