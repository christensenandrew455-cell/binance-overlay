package com.decisionoverlay.checker

import android.Manifest
import android.content.Intent
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

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val symbol = findViewById<EditText>(R.id.symbolInput)
        val apiKey = findViewById<EditText>(R.id.apiKeyInput)
        symbol.setText(prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT"))
        val savedKey = prefs.getString(OverlayService.KEY_API_KEY, "").orEmpty()
        apiKey.setText(savedKey.ifBlank { BuildConfig.BINANCE_API_KEY })
        findViewById<Button>(R.id.permissionButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            setStatus("Overlay stopped.")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.permissionState).text = if (granted) {
            "Draw over other apps: granted"
        } else {
            "Draw over other apps: not granted"
        }
    }

    private fun startOverlay() {
        val symbol = findViewById<EditText>(R.id.symbolInput).text?.toString()?.trim()?.uppercase().orEmpty()
        val typedKey = findViewById<EditText>(R.id.apiKeyInput).text?.toString()?.trim().orEmpty()
        val apiKey = typedKey.ifBlank { BuildConfig.BINANCE_API_KEY.trim() }
        if (!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))) {
            setStatus("Use a Binance symbol such as BTCUSDT, ETHUSDT, or SOLUSDT.")
            return
        }
        if (apiKey.isEmpty()) {
            setStatus("BINANCE_API_KEY is not set. That environment variable is the Binance API key.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Grant Draw over other apps, then come back and press Show overlay.")
            openOverlaySettings()
            return
        }
        prefs.edit()
            .putString(OverlayService.KEY_SYMBOL, symbol)
            .putString(OverlayService.KEY_API_KEY, apiKey)
            .apply()
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_SYMBOL, symbol)
            .putExtra(OverlayService.EXTRA_API_KEY, apiKey)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Overlay is calling the Binance REST API for $symbol. No websocket. Drag the card, tap the header to shrink it.")
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun setStatus(text: String) {
        findViewById<TextView>(R.id.statusText).text = text
    }
}
