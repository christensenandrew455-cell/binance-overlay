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
        else if (symbol != null) setStatus("Notification permission is needed for background status and UP / DOWN alerts.")
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
                findViewById<Button>(R.id.startCheckButton).text = "START CHECKING"
            }
            setStatus("Checker stopped. Background checks and alerts are off.")
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener {
            if (checksStarted) engine.refreshNow() else setStatus("Press START CHECKING first.")
        }
        engine = MarketEngine(screenScope, { snapshot -> runOnUiThread { render(snapshot) } }, prefs.getInt(OverlayService.KEY_STRICTNESS, 1))
    }

    override fun onConfigurationChanged(newConfig: Configuration) { super.onConfigurationChanged(newConfig) }
    override fun onDestroy() { engine.stop(); screenScope.cancel(); super.onDestroy() }

    private fun startChecks() {
        val symbol = readSymbol() ?: return
        prefs.edit().putString(OverlayService.KEY_SYMBOL, symbol).apply()
        if (!checksStarted) {
            checksStarted = true
            engine.start(symbol)
            setStatus("Checking $symbol. The 1-hour candle direction is filtered by normalized steepness (minimum 4.0/10).")
            findViewById<Button>(R.id.startCheckButton).text = "CHECKING • RUNNING"
        } else {
            if (symbol != engine.symbol) engine.updateSymbol(symbol) else engine.refreshNow()
            setStatus("Refreshed $symbol. The hourly steepness signal refreshes with live Binance data.")
        }
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
        val decision = snapshot.decision
        findViewById<TextView>(R.id.symbolHeading).text = snapshot.symbol + " • BTC $" + fmtPrice(decision.currentPrice)
        findViewById<TextView>(R.id.targetText).text = "1-HOUR STEEPNESS: " + (decision.steepnessScore?.let { String.format(java.util.Locale.US, "%.1f/10", it) } ?: "WAITING")
        findViewById<TextView>(R.id.windowText).text = "SIGNAL RULE: UP/DOWN only at 4.0/10 or higher"
        findViewById<TextView>(R.id.overallStatus).apply {
            text = when (decision.verdict) {
                Verdict.UP -> "UP"
                Verdict.DOWN -> "DOWN"
                Verdict.DNT -> "DNT"
            } + "   •   STEEPNESS " + (decision.steepnessScore?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "—") + "/10
            setTextColor(ContextCompat.getColor(this@MainActivity, when (decision.verdict) {
                Verdict.UP -> R.color.up
                Verdict.DOWN -> R.color.down
                Verdict.DNT -> R.color.dnt
            }))
        }
        findViewById<TextView>(R.id.reasonText).text = decision.reason
        findViewById<TextView>(R.id.problemsText).text = if (decision.problems.isEmpty()) "No data blockers." else decision.problems.joinToString("\n") { "• " + it }
        findViewById<TextView>(R.id.logText).text = snapshot.logText.ifBlank { "Waiting for first data update…" }
    }

    private fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) + 999L) / 1000L
        return String.format(java.util.Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
    }

    private fun startOverlay() {
        val symbol = readSymbol() ?: return
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Android needs permission for the floating decision. Opening permission settings.")
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
        setStatus(if (showOverlay) "Floating decision and automatic 15-minute checker running for $symbol."
        else "Background checker running for $symbol. Use STOP RUNNING to turn it off.")
    }

    private fun setStatus(message: String) { findViewById<TextView>(R.id.statusText).text = message }
}
