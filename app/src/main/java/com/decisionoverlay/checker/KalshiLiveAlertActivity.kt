package com.decisionoverlay.checker

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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

/** View and control the opt-out read-only live Kalshi price-pattern alerts. */
class KalshiLiveAlertActivity : AppCompatActivity() {
    private val timer = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var lastAlert: TextView
    private lateinit var start: Button
    private lateinit var stop: Button

    private val refresh = object : Runnable {
        override fun run() {
            val p = getSharedPreferences(KalshiLiveSignalService.PREFS, MODE_PRIVATE)
            val enabled = p.getBoolean(KalshiLiveSignalService.ENABLED, true)
            status.text = (if (enabled) "ENABLED" else "OFF") + "\n" +
                p.getString(KalshiLiveSignalService.STATUS, "Waiting for monitoring...")
            lastAlert.text = p.getString(KalshiLiveSignalService.LAST_ALERT,
                "No qualifying live alerts yet.")
            start.isEnabled = !enabled
            stop.isEnabled = enabled
            timer.postDelayed(this, 1500L)
        }
    }

    private fun dp(x: Int) = (x * resources.displayMetrics.density).toInt()
    private fun label(text: String, size: Float, color: Int) = TextView(this).apply {
        this.text = text
        this.textSize = size
        setTextColor(color)
        setPadding(0, dp(5), 0, dp(5))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        window.statusBarColor = Color.rgb(7, 10, 16)
        window.navigationBarColor = Color.rgb(7, 10, 16)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(15), dp(15), dp(15), dp(15))
            setBackgroundColor(Color.rgb(7, 10, 16))
        }
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)

        root.addView(label("Kalshi LIVE Pattern Alerts", 23f, Color.WHITE))
        root.addView(label(
            "Binance.US BTC closed-candle patterns. Four research-only watch " +
            "alerts, not live Kalshi odds, guaranteed outcomes, or trades.", 13f,
            Color.rgb(195, 206, 219)))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        start = Button(this).apply {
            text = "ENABLE ALERTS"
            setAllCaps(false)
            setOnClickListener {
                getSharedPreferences(KalshiLiveSignalService.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KalshiLiveSignalService.ENABLED, true).apply()
                ContextCompat.startForegroundService(this@KalshiLiveAlertActivity,
                    Intent(this@KalshiLiveAlertActivity,
                        KalshiLiveSignalService::class.java)
                        .setAction(KalshiLiveSignalService.START))
                start.isEnabled = false
            }
        }
        stop = Button(this).apply {
            text = "STOP ALERTS"
            setAllCaps(false)
            setOnClickListener {
                getSharedPreferences(KalshiLiveSignalService.PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KalshiLiveSignalService.ENABLED, false).apply()
                startService(Intent(this@KalshiLiveAlertActivity,
                    KalshiLiveSignalService::class.java)
                    .setAction(KalshiLiveSignalService.STOP))
                stop.isEnabled = false
            }
        }
        buttons.addView(start, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stop, LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(buttons)
        status = label("Connecting...", 14f, Color.rgb(242, 190, 74))
        root.addView(status)
        root.addView(label("LAST ALERT", 14f, Color.WHITE))
        lastAlert = label("", 13f, Color.rgb(88, 220, 143))
        root.addView(lastAlert)
        root.addView(label("FOUR SELECTED 20%+ OCCURRENCE RULES", 16f, Color.WHITE))
        root.addView(label(
            "Only the four selected exact signed-bin paths generate research " +
            "alerts; one alert per BTC 15-minute market. '20%' means the path " +
            "was found in at least 200 of 1,000 historical markets. " +
            "Any individual exact-minute window may occur much less often.",
            12f, Color.rgb(195, 206, 219)))
        for (rule in KalshiLiveSignalRules.rules) {
            root.addView(label(
                rule.name + " | BUY " + rule.side +
                " | hit " + String.format(Locale.US, "%.1f%%", rule.hitRatePercent) +
                " | win " + String.format(Locale.US, "%.1f%%", rule.winRatePercent) +
                " | newest win " + String.format(Locale.US, "%.1f%%",
                    rule.newerWinPercent),
                12f, Color.rgb(221, 230, 241)))
        }
        root.addView(label(
            "Observed settlement outcomes do not guarantee profitable trades. " +
            "Check live contract prices, spreads and fees before entering manually.",
            12f, Color.rgb(190, 201, 212)))
    }

    override fun onResume() {
        super.onResume()
        timer.removeCallbacks(refresh)
        timer.post(refresh)
    }

    override fun onPause() {
        timer.removeCallbacks(refresh)
        super.onPause()
    }
}
