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

/** Displays and copies the same completed indicator report. */
class KalshiPatternResultsActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var test: Button
    private lateinit var copy: Button
    private lateinit var status: TextView
    private lateinit var output: TextView
    private var shownReport = ""
    private val refresh = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences(KalshiPatternBacktestService.PREFS, Context.MODE_PRIVATE)
            val running = KalshiPatternBacktestService.isScanning()
            val report = prefs.getString(KalshiPatternBacktestService.EDGE_RESULT, "").orEmpty()
            status.text = prefs.getString(KalshiPatternBacktestService.INDICATOR_STATUS, "Tap Test to scan 1,000 BTC markets.")
            test.isEnabled = !running
            copy.isEnabled = !running && report.isNotEmpty()
            if (report != shownReport) {
                shownReport = report
                output.text = report
            }
            handler.postDelayed(this, 500L)
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
            text = "BTC indicator test"
            textSize = 19f
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, dp(5))
        }
        root.addView(heading)
        val commands = row(root)
        test = button("Test") {
            test.isEnabled = false
            copy.isEnabled = false
            ContextCompat.startForegroundService(this, Intent(this, KalshiPatternBacktestService::class.java)
                .setAction(KalshiPatternBacktestService.EDGE))
        }
        copy = button("Copy") {
            (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("BTC indicator results", shownReport))
            Toast.makeText(this, "Results copied", Toast.LENGTH_SHORT).show()
        }
        addButton(commands, test)
        addButton(commands, copy)
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
