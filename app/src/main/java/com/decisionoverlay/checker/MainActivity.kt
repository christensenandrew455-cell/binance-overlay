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
        setStatus("Reading 100 one-minute candles. EMA 7 and outlier checks update every 5 seconds.")
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
        findViewById<TextView>(R.id.targetText).text = metrics.displayText()
        findViewById<TextView>(R.id.overallStatus).text = when {
            metrics.priceAnomaly == true || metrics.volumeAnomaly == true || metrics.momentumAnomaly == true -> "UNUSUAL MOVEMENT DETECTED"
            metrics.candleCount < 15 -> "COLLECTING CANDLES"
            else -> "WITHIN NORMAL RANGE"
        }
        findViewById<TextView>(R.id.reasonText).text =
            "EMA 7 is calculated locally from Binance.US 1-minute candle close, volume, and close-to-close momentum. Outliers are flagged when the newest value is more than 2 standard deviations from the previous 14 candles."
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
        setStatus(if (showOverlay) "Floating EMA 7 monitor running for $symbol."
        else "EMA 7 monitor running for $symbol in the background.")
    }

    private fun setStatus(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }
}
