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
        symbol.setText(prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT"))
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
        if (!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))) {
            setStatus("Use a Binance symbol such as BTCUSDT, ETHUSDT, or SOLUSDT.")
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            setStatus("Grant Draw over other apps, then come back and press Show overlay.")
            openOverlaySettings()
            return
        }
        prefs.edit().putString(OverlayService.KEY_SYMBOL, symbol).apply()
        val intent = Intent(this, OverlayService::class.java).putExtra(OverlayService.EXTRA_SYMBOL, symbol)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Overlay is running for $symbol. You can leave this screen. Drag the card, tap the header to shrink it.")
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
