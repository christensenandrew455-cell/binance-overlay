package com.decisionoverlay.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** EMA slope research plus separate daily P&L and reversal simulations. */
class KalshiPatternResultsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var test: Button
    private lateinit var daily: Button
    private lateinit var reversal: Button
    private var reversalMode = false
    private var dailyMode = false
    private lateinit var copy: Button
    private lateinit var save: Button
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var shownReport = ""
    private var pendingExportKey: String? = null

    private val exportPicker = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        val key = pendingExportKey
        pendingExportKey = null
        if (uri == null || key == null) return@registerForActivityResult
        val contents = getSharedPreferences(KalshiPatternBacktestService.PREFS, MODE_PRIVATE)
            .getString(key, null)
        if (contents.isNullOrEmpty()) {
            Toast.makeText(this, "Run TEST first.", Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        Thread {
            val saved = runCatching {
                contentResolver.openOutputStream(uri, "wt")
                    ?.bufferedWriter(Charsets.UTF_8)?.use { it.write(contents) }
                    ?: error("Could not open selected file")
            }
            runOnUiThread {
                Toast.makeText(this, if (saved.isSuccess)
                    "Saved full report." else "Save failed: " +
                        (saved.exceptionOrNull()?.message ?: "storage error"),
                    Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(KalshiPatternBacktestService.PREFS, MODE_PRIVATE)
            val running = KalshiPatternBacktestService.isScanning()
            val key = if (reversalMode) KalshiPatternBacktestService.REVERSAL_RESULT
                else if (dailyMode) KalshiPatternBacktestService.DAILY_RESULT
                else KalshiPatternBacktestService.FOCUSED_RESULT
            val statusKey = if (reversalMode) KalshiPatternBacktestService.REVERSAL_STATUS
                else if (dailyMode) KalshiPatternBacktestService.DAILY_STATUS
                else KalshiPatternBacktestService.FOCUSED_STATUS
            val report = prefs.getString(key, "").orEmpty()
            status.text = prefs.getString(statusKey, if (reversalMode)
                "REVERSAL TEST compares 1,000 markets; download full CSV. No Kalshi price data."
                else if (dailyMode)
                "DAILY TEST simulates 1,000 markets using the current buy rules."
                else "TEST measures 5m EMA 25 slope (12 candles) at 15 through 1 minutes left across 1,000 markets.")
            test.isEnabled = !running
            daily.isEnabled = !running
            reversal.isEnabled = !running
            copy.isEnabled = !running && report.isNotEmpty()
            save.isEnabled = !running && report.isNotEmpty()
            if (report != shownReport) {
                shownReport = report
                output.text = if (report.length > 30_000)
                    report.take(30_000) + "\n\n[Preview shortened. Download for every row.]"
                else report
            }
            handler.postDelayed(this, 500L)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(name: String, action: () -> Unit) = Button(this).apply {
        text = name
        textSize = 11f
        setAllCaps(false)
        setOnClickListener { action() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExportKey = savedInstanceState?.getString("pending_export_key")
        val settings = getSharedPreferences(KalshiPatternBacktestService.PREFS, MODE_PRIVATE)
        dailyMode = savedInstanceState?.getBoolean("daily_mode")
            ?: settings.getBoolean("daily_view_v1", false)
        reversalMode = savedInstanceState?.getBoolean("reversal_mode")
            ?: settings.getBoolean("reversal_view_v1", false)
        supportActionBar?.hide()
        window.statusBarColor = Color.rgb(7, 10, 16)
        window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 16))
        }
        root.addView(TextView(this).apply {
            text = "BTC historical backtest"
            textSize = 19f
            setTextColor(Color.WHITE)
        })
        val commands = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        root.addView(commands)
        test = button("TEST") {
            dailyMode = false
            reversalMode = false
            settings.edit().putBoolean("daily_view_v1", false).putBoolean("reversal_view_v1", false).apply()
            output.text = ""
            shownReport = ""
            test.isEnabled = false
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.FOCUSED))
        }
        copy = button("COPY") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(
                ClipData.newPlainText("BTC backtest results", shownReport))
            Toast.makeText(this, "Results copied", Toast.LENGTH_SHORT).show()
        }
        save = button("DOWNLOAD") {
            val key = if (reversalMode) KalshiPatternBacktestService.REVERSAL_RESULT
                else if (dailyMode) KalshiPatternBacktestService.DAILY_CSV
                else KalshiPatternBacktestService.FOCUSED_RESULT
            val hasReport = getSharedPreferences(
                KalshiPatternBacktestService.PREFS, Context.MODE_PRIVATE
            ).getString(key, null)?.isNotEmpty() == true
            if (!hasReport) {
                Toast.makeText(this, "Run TEST first.", Toast.LENGTH_LONG).show()
            } else {
                pendingExportKey = key
                val time = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                exportPicker.launch(if (reversalMode) "BTC-reversal-$time.csv"
                    else if (dailyMode) "BTC-daily-PnL-$time.csv"
                    else "BTC-EMA-timing-$time.csv")
            }
        }
        for (b in listOf(test, copy, save)) {
            commands.addView(b, LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(TextView(this).apply {
            text = "Daily test: \$10 per BUY · 60¢ reference · 1.4x payout\nWin +\$4 / Loss -\$10 (no extra fees)"
            textSize = 12f
            setTextColor(Color.WHITE)
        })
        daily = button("DAILY TEST — 1,000 MARKETS") {
            dailyMode = true
            reversalMode = false
            settings.edit().putBoolean("daily_view_v1", true).putBoolean("reversal_view_v1", false).apply()
            output.text = ""
            shownReport = ""
            daily.isEnabled = false
            test.isEnabled = false
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.DAILY))
        }
        root.addView(daily)
        reversal = button("REVERSAL & TIMING TEST — 1,000 MARKETS") {
            reversalMode = true
            dailyMode = false
            settings.edit().putBoolean("daily_view_v1", false).putBoolean("reversal_view_v1", true).apply()
            output.text = ""
            shownReport = ""
            reversal.isEnabled = false
            daily.isEnabled = false
            test.isEnabled = false
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.REVERSAL))
        }
        root.addView(reversal)
        status = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.rgb(240, 190, 80))
            setPadding(0, dp(6), 0, dp(5))
        }
        root.addView(status)
        val scroller = ScrollView(this).apply { isFillViewport = true }
        output = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(214, 224, 232))
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(12))
        }
        scroller.addView(output)
        root.addView(scroller, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(refresh)
        handler.post(refresh)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pending_export_key", pendingExportKey)
        outState.putBoolean("daily_mode", dailyMode)
        outState.putBoolean("reversal_mode", reversalMode)
        super.onSaveInstanceState(outState)
    }
    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
