package com.decisionoverlay.checker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/** One-screen historical checker. All legacy charts, trade alerts and menus removed. */
class BtcRangeBacktestActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var check: Button
    private lateinit var stop: Button
    private lateinit var status: TextView
    private lateinit var result: TextView
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val refreshTask = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 20, 22, 20)
            setBackgroundColor(Color.rgb(17, 24, 34))
        }
        fun text(value: String, size: Float): TextView = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(Color.WHITE)
            setTextIsSelectable(true)
            setPadding(0, 11, 0, 11)
        }
        root.addView(text("BTC — 100-MARKET CHECK", 22f))
        root.addView(text(
            "Binance.US • 10 AM–11 PM Eastern • 15-minute sections\n" +
            "60 one-minute candles × minutes remaining • first hit counts once\n" +
            "Compare the 50–90% rule with SIMULATED Kalshi-style 60–79c prices. " +
            "No live trading or actual historical Kalshi quotes.", 13f))
        status = text("Press CHECK to backtest 100 finished markets.", 15f)
        root.addView(status)
        check = Button(this).apply {
            text = "CHECK"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@BtcRangeBacktestActivity,
                        Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED)
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(this@BtcRangeBacktestActivity,
                    Intent(this@BtcRangeBacktestActivity,
                        BtcRangeBacktestService::class.java)
                        .setAction(BtcRangeBacktestService.START))
                refresh()
            }
        }
        stop = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                startService(Intent(this@BtcRangeBacktestActivity,
                    BtcRangeBacktestService::class.java)
                    .setAction(BtcRangeBacktestService.STOP))
            }
        }
        root.addView(check)
        root.addView(stop)
        result = text("", 17f)
        root.addView(result)
        root.addView(Button(this).apply {
            text = "COPY RESULTS"
            setOnClickListener {
                val content = result.text.toString()
                if (content.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("BTC range", content))
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        refresh()
    }
    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refreshTask)
        handler.post(refreshTask)
    }
    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        super.onPause()
    }
    private fun refresh() {
        if (!::status.isInitialized) return
        val prefs = getSharedPreferences(BtcRangeBacktestService.PREFS, MODE_PRIVATE)
        val phase = prefs.getString("phase", "idle")
        status.text = prefs.getString("status", "Press CHECK to begin.")
        result.text = prefs.getString("report", "").orEmpty()
        check.isEnabled = phase != "running"
        stop.isEnabled = phase == "running"
    }
}
