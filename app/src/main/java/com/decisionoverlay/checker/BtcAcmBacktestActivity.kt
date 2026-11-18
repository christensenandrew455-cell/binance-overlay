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

/**
 * An on-device replay comparing 3/15/30/60-candle ACM rules.
 * Historical only: it never places orders or sends trade alerts.
 */
class BtcAcmBacktestActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var results: TextView
    private lateinit var go: Button
    private lateinit var stop: Button
    private lateinit var profitGo: Button
    private lateinit var profitStop: Button
    private lateinit var profitStatus: TextView
    private lateinit var profitResults: TextView
    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}
    private val refreshTask = object : Runnable {
        override fun run() {
            showSaved()
            main.postDelayed(this, 1500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 18, 22, 22)
            setBackgroundColor(Color.rgb(16, 21, 29))
        }
        fun text(value: String, size: Float) = TextView(this).apply {
            this.text = value
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 10)
            setTextIsSelectable(true)
        }
        root.addView(text("BTC — 3 / 15 / 30 / 60 CANDLE ACM TEST", 21f))
        root.addView(text(
            "ACM = average of (high minus low) / open, as a percentage, " +
            "over the last 3, 15, 30 or 60 COMPLETED 1-minute candles. " +
            "For each candidate, threshold = ACM × minutes left. " +
            "When BTC reaches or exceeds that distance from the target, " +
            "the replay checks whether it closes on that same side and whether " +
            "any later candle touches the target. " +
            "Second half only: 7–14 minutes elapsed. 10 AM–11 PM New York. " +
            "The 95% stay/no-touch rate is a HYPOTHESIS to test, not an assumed probability.", 13f
        ))
        go = Button(this).apply {
            text = "RUN ALL FOUR BACKTESTS (UP TO 120 DAYS)"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@BtcAcmBacktestActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(
                    this@BtcAcmBacktestActivity,
                    Intent(this@BtcAcmBacktestActivity, BtcAcmBacktestService::class.java)
                        .setAction(BtcAcmBacktestService.START)
                )
                showSaved()
            }
        }
        stop = Button(this).apply {
            text = "STOP BACKTEST"
            setOnClickListener {
                startService(
                    Intent(this@BtcAcmBacktestActivity, BtcAcmBacktestService::class.java)
                        .setAction(BtcAcmBacktestService.STOP)
                )
            }
        }
        root.addView(go)
        root.addView(stop)
        root.addView(Button(this).apply {
            text = "COPY FULL BACKTEST RESULTS"
            setOnClickListener {
                val report = getSharedPreferences(BtcAcmBacktestService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (report.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("BTC ACM four-way test", report))
            }
        })
        root.addView(text(
            "NEW — KALSHI PROFIT TEST: 100 historical BTC markets, 60-candle ACM, " +
            "K from 0 to 2, and all ten-cent entry-price bands. " +
            "A maximum of one trade per market for each K / price-band strategy; " +
            "$10 total entry budget including Kalshi's taker fee. " +
            "Uses Kalshi's actual settlement result and minute quote data. " +
            "Historical asks are indicative, not guaranteed fills. " +
            "Background scan continues with the screen off.", 14f
        ))
        profitGo = Button(this).apply {
            text = "RUN 100-MARKET PROFIT + KALSHI FEE TEST"
            setOnClickListener {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        this@BtcAcmBacktestActivity, Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED
                ) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(
                    this@BtcAcmBacktestActivity,
                    Intent(this@BtcAcmBacktestActivity, BtcAcmProfitService::class.java)
                        .setAction(BtcAcmProfitService.START)
                )
                showSaved()
            }
        }
        profitStop = Button(this).apply {
            text = "STOP PROFIT TEST"
            setOnClickListener {
                startService(Intent(this@BtcAcmBacktestActivity,
                    BtcAcmProfitService::class.java).setAction(BtcAcmProfitService.STOP))
            }
        }
        root.addView(profitGo)
        root.addView(profitStop)
        root.addView(Button(this).apply {
            text = "COPY ALL KALSHI PROFIT RESULTS"
            setOnClickListener {
                val content = getSharedPreferences(BtcAcmProfitService.PREFS, MODE_PRIVATE)
                    .getString("report", "").orEmpty()
                if (content.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("Kalshi BTC profit test", content))
            }
        })
        profitStatus = text("Run profit test to compare ACM thresholds and contract prices.", 15f)
        profitResults = text("", 13f)
        root.addView(profitStatus)
        root.addView(profitResults)
        root.addView(Button(this).apply {
            text = "OPEN PREVIOUS FUNNEL BACKTEST"
            setOnClickListener {
                startActivity(Intent(this@BtcAcmBacktestActivity,
                    BtcHalfwayTestActivity::class.java))
            }
        })
        status = text("Press RUN to compare all four windows.", 15f)
        results = text("", 13f)
        root.addView(status)
        root.addView(results)
        setContentView(ScrollView(this).apply { addView(root) })
        showSaved()
    }

    override fun onResume() {
        super.onResume()
        main.removeCallbacks(refreshTask)
        main.post(refreshTask)
    }

    override fun onPause() {
        main.removeCallbacks(refreshTask)
        super.onPause()
    }

    private fun showSaved() {
        if (!::status.isInitialized) return
        val p = getSharedPreferences(BtcAcmBacktestService.PREFS, MODE_PRIVATE)
        val phase = p.getString("phase", "idle")
        status.text = p.getString("status", "Press RUN to compare the four windows.")
        results.text = p.getString("report", "")
        go.isEnabled = phase != "running" && phase != "stopping"
        stop.isEnabled = phase == "running" || phase == "stopping"
        val profitPrefs = getSharedPreferences(BtcAcmProfitService.PREFS, MODE_PRIVATE)
        val profitPhase = profitPrefs.getString("phase", "idle")
        profitStatus.text = profitPrefs.getString("status",
            "Press RUN 100-MARKET PROFIT TEST to scan Kalshi quotes.")
        profitResults.text = profitPrefs.getString("report", "")
        profitGo.isEnabled = profitPhase != "running" && profitPhase != "stopping"
        profitStop.isEnabled = profitPhase == "running" || profitPhase == "stopping"
    }
}
