package com.decisionoverlay.checker

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
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
import androidx.core.content.ContextCompat

/** Screen for starting/stopping the phone-safe scan and copying the raw results. */
class KalshiPatternResultsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var copy: Button
    private lateinit var share: Button
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var shownReport = ""
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(KalshiPatternBacktestService.PREFS,
                Context.MODE_PRIVATE)
            val running = prefs.getBoolean(KalshiPatternBacktestService.RUNNING, false)
            val report = prefs.getString(KalshiPatternBacktestService.REPORT, "").orEmpty()
            status.text = prefs.getString(KalshiPatternBacktestService.STATUS,
                "Ready. Press RUN BACKTEST to check 1,000 settled markets.")
            start.isEnabled = !running
            stop.isEnabled = running
            copy.isEnabled = report.isNotEmpty()
            share.isEnabled = report.isNotEmpty()
            if (report != shownReport) {
                shownReport = report
                output.text = if (report.isEmpty())
                    "Results will appear here when the scan finishes.\n\n" +
                    "This scanner checks ACTUAL Kalshi YES/NO quote candles and settled outcomes. " +
                    "The chart's custom Binance-based lines are not Kalshi probabilities.\n\n" +
                    "The test checks 1,000 BTC markets from 10 AM to 11 PM Eastern. " +
                    "It covers YES/NO, all 15-to-0 minute bins, 60/70/80/90 hits, " +
                    "every lower 10%-step level, and recovery sequences (like 80-60-70).\n\n" +
                    "You can turn your screen off while it runs."
                else report
            }
            handler.postDelayed(this, 1000L)
        }
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun button(label: String, handler: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        setOnClickListener { handler() }
        setAllCaps(false)
    }
    private fun row(parent: LinearLayout): LinearLayout {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        parent.addView(r)
        return r
    }
    private fun addButton(parent: LinearLayout, button: Button) {
        parent.addView(button, LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.rgb(7, 10, 16)
        window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            setPadding(dp(12), dp(10), dp(12), dp(10))
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(7, 10, 16))
        }
        val heading = TextView(this).apply {
            text = "Kalshi Pattern Backtest"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(5))
        }
        root.addView(heading)
        val commands = row(root)
        start = button("RUN BACKTEST (1000)") {
            ContextCompat.startForegroundService(this,
                Intent(this, KalshiPatternBacktestService::class.java)
                    .setAction(KalshiPatternBacktestService.START))
            start.isEnabled = false
        }
        stop = button("STOP") {
            startService(Intent(this, KalshiPatternBacktestService::class.java)
                .setAction(KalshiPatternBacktestService.STOP))
        }
        addButton(commands, start)
        addButton(commands, stop)
        val actions = row(root)
        copy = button("COPY RESULTS") {
            val report = shownReport
            if (report.isNotEmpty()) {
                val mgr = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                mgr.setPrimaryClip(ClipData.newPlainText("Kalshi Backtest", report))
                Toast.makeText(this, "Full report copied", Toast.LENGTH_SHORT).show()
            }
        }
        share = button("SHARE REPORT") {
            if (shownReport.isNotEmpty()) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "Kalshi BTC historical pattern report")
                    putExtra(Intent.EXTRA_TEXT, shownReport)
                }
                startActivity(Intent.createChooser(send, "Share Kalshi results"))
            }
        }
        addButton(actions, copy)
        addButton(actions, share)
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

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }
}
