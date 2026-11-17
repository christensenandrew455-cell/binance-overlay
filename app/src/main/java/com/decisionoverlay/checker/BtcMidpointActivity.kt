package com.decisionoverlay.checker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Single-purpose BTC 0.375% research screen. The scan lives in a separate
 * foreground service, NOT this Activity: leaving or locking the phone does
 * not call stop on the running job.
 */
class BtcMidpointActivity : AppCompatActivity() {
    private val refreshHandler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var run: Button
    private lateinit var stop: Button
    private var reportText = ""

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this,
            "Notifications are disabled; enable them to receive completion/failure alerts.",
            Toast.LENGTH_LONG).show()
        launchStudy()
    }

    private val refreshJob = object : Runnable {
        override fun run() {
            showSavedResults()
            refreshHandler.postDelayed(this, 1300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 22, 24, 28)
            setBackgroundColor(0xFF10151D.toInt())
        }
        fun label(value: String, font: Float, bold: Boolean = false) =
            TextView(this).apply {
                text = value
                textSize = font
                setTextColor(0xFFEAF1F9.toInt())
                setPadding(0, 12, 0, 12)
                setTextIsSelectable(true)
                if (bold) typeface = Typeface.DEFAULT_BOLD
            }
        root.addView(label("BTC 0.375% MIDPOINT TEST", 23f, true))
        root.addView(label(
            "Only BTC. Only 10 AM–11 PM New York. Look at XX:07 and XX:08 " +
                "(7 and 8 minutes elapsed). Find 100 historical markets at each " +
                "minute with Bitcoin 0.350%–0.400% above or below the target. " +
                "Show how often it STAYED, CROSSED, TIED, or TOUCHED the target. " +
                "Positive and negative cases are counted separately. " +
                "No trading orders; Binance.US is a proxy, not Kalshi settlement.",
            13f
        ))
        run = Button(this).apply {
            text = "RUN BTC TEST — 100 MATCHES EACH MINUTE"
            setOnClickListener { requestAndStart() }
        }
        stop = Button(this).apply {
            text = "STOP TEST"
            setOnClickListener {
                startService(Intent(this@BtcMidpointActivity,
                    BtcMidpointService::class.java).apply {
                        action = BtcMidpointService.ACTION_STOP
                    })
                showSavedResults()
            }
        }
        root.addView(run)
        root.addView(stop)
        status = label("Ready to test.", 15f, true)
        root.addView(status)
        root.addView(Button(this).apply {
            text = "COPY RESULTS"
            setOnClickListener {
                (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText(
                        "BTC midpoint historical test", reportText))
                Toast.makeText(this@BtcMidpointActivity,
                    "Results copied", Toast.LENGTH_SHORT).show()
            }
        })
        results = label("", 13f)
        root.addView(results)
        setContentView(ScrollView(this).apply { addView(root) })
        showSavedResults()
    }

    private fun requestAndStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else launchStudy()
    }

    private fun launchStudy() {
        val prefs = getSharedPreferences(BtcMidpointService.PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(BtcMidpointService.KEY_RUNNING, false)) {
            Toast.makeText(this, "The test is already running", Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this,
            Intent(this, BtcMidpointService::class.java).apply {
                action = BtcMidpointService.ACTION_START
            })
        status.text = "Starting the background BTC scan…"
    }

    private fun showSavedResults() {
        if (!::status.isInitialized || !::results.isInitialized) return
        val prefs = getSharedPreferences(BtcMidpointService.PREFS, MODE_PRIVATE)
        val running = prefs.getBoolean(BtcMidpointService.KEY_RUNNING, false)
        status.text = prefs.getString(BtcMidpointService.KEY_STATUS,
            "Ready to test.") ?: "Ready to test."
        reportText = prefs.getString(BtcMidpointService.KEY_REPORT, "") ?: ""
        results.text = if (reportText.isEmpty())
            "Run the test, then leave this app or lock the screen if you want. " +
                "Progress stays in the notification. Results and any error " +
                "will be here when you return."
        else reportText
        run.isEnabled = !running
        stop.isEnabled = running
    }

    override fun onResume() {
        super.onResume()
        refreshHandler.removeCallbacks(refreshJob)
        refreshHandler.post(refreshJob)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshJob)
        super.onPause()
    }
}
