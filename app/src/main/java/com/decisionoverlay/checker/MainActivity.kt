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
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(OverlayService.PREFS, MODE_PRIVATE) }
    private var pendingSymbol: String? = null

    private val notifications = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        pendingSymbol?.let(::launchOverlay)
        pendingSymbol = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<EditText>(R.id.symbolInput).setText(
            prefs.getString(OverlayService.KEY_SYMBOL, "BTCUSDT"),
        )
        findViewById<Button>(R.id.permissionButton).setOnClickListener { openOverlaySettings() }
        findViewById<Button>(R.id.startButton).setOnClickListener { startOverlay() }
        findViewById<Button>(R.id.stopButton).setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
            setStatus("Floating checker stopped.")
        }

        window.decorView.post {
            if (!Settings.canDrawOverlays(this)) {
                AlertDialog.Builder(this)
                    .setTitle("Allow the floating checker")
                    .setMessage("Android needs permission to show the UP / DOWN / DNT panel over other apps. No trades are placed.")
                    .setPositiveButton("Open permission settings") { _, _ -> openOverlaySettings() }
                    .setNegativeButton("Not now", null)
                    .show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val granted = Settings.canDrawOverlays(this)
        findViewById<TextView>(R.id.permissionState).apply {
            text = if (granted) "FLOATING PERMISSION  •  GRANTED" else "FLOATING PERMISSION  •  NEEDED"
            setTextColor(ContextCompat.getColor(this@MainActivity, if (granted) R.color.up else R.color.dnt))
        }
    }

    private fun startOverlay() {
        val symbol = findViewById<EditText>(R.id.symbolInput)
            .text?.toString()?.trim()?.uppercase().orEmpty()

        if (!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))) {
            setStatus("Enter a Binance spot symbol, for example BTCUSDT, ETHUSDT, or SOLUSDT.")
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            setStatus("Allow Draw over other apps, then return here and tap Start checker.")
            openOverlaySettings()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingSymbol = symbol
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }

        launchOverlay(symbol)
    }

    private fun launchOverlay(symbol: String) {
        prefs.edit().putString(OverlayService.KEY_SYMBOL, symbol).apply()
        val intent = Intent(this, OverlayService::class.java)
            .putExtra(OverlayService.EXTRA_SYMBOL, symbol)
        ContextCompat.startForegroundService(this, intent)
        setStatus("Starting $symbol. Reading public Binance market data — no API key required.")
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        startActivity(intent)
    }

    private fun setStatus(message: String) {
        findViewById<TextView>(R.id.statusText).text = message
    }
}
