package com.decisionoverlay.checker

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

/**
 * Local, fingerprint-locked shell. There is deliberately no public web
 * dashboard and no API secret in this APK.
 *
 * IMPORTANT: controls remain disabled until an authenticated, persistent
 * private backend is wired up. Never report invented balances or wins.
 */
class KalshiTradingDashboardActivity : AppCompatActivity() {
    private val bg = Color.rgb(5, 7, 12)
    private val panel = Color.rgb(17, 23, 34)
    private val white = Color.rgb(237, 241, 248)
    private val muted = Color.rgb(150, 164, 183)
    private val green = Color.rgb(66, 221, 139)
    private val red = Color.rgb(239, 94, 106)
    private var unlocked = false
    private var promptVisible = false
    private lateinit var shell: LinearLayout

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        supportActionBar?.hide()
        shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(24), dp(18), dp(24), dp(18))
        }
        setContentView(shell)
        lockedScreen()
        Handler(Looper.getMainLooper()).postDelayed({ if (!isFinishing) unlock() }, 350)
    }

    private fun label(text: String, size: Float = 16f, color: Int = white): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(7), dp(8), dp(7))
        }

    private fun lockedScreen(message: String = "Use your fingerprint to open the trading dashboard") {
        unlocked = false
        shell.removeAllViews()
        shell.gravity = Gravity.CENTER
        shell.addView(label("KALSHI", 26f))
        shell.addView(label(message, 14f, muted))
        shell.addView(Button(this).apply {
            text = "UNLOCK WITH FINGERPRINT"
            setAllCaps(false)
            setOnClickListener { unlock() }
        })
    }

    private fun unlock() {
        if (unlocked || promptVisible || isFinishing) return
        if (BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG) != BiometricManager.BIOMETRIC_SUCCESS
        ) {
            lockedScreen("Enroll a strong fingerprint in Android Settings to unlock.")
            return
        }
        promptVisible = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    promptVisible = false
                    unlocked = true
                    dashboard()
                }
                override fun onAuthenticationError(code: Int, message: CharSequence) {
                    super.onAuthenticationError(code, message)
                    promptVisible = false
                    if (!unlocked) lockedScreen("Fingerprint required. Tap to try again.")
                }
                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    // A failed attempt never reveals private data.
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kalshi Trader")
            .setSubtitle("Verify your fingerprint")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build())
    }

    private fun dashboard() {
        if (!unlocked) return
        shell.removeAllViews()
        shell.gravity = Gravity.TOP
        val scroller = ScrollView(this).apply { isFillViewport = true }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(8), dp(6), dp(18))
        }
        scroller.addView(body)
        shell.addView(scroller)
        body.addView(label("KALSHI TRADER", 22f))
        body.addView(label("TRADING STOPPED · WORKER NOT CONNECTED", 12f, muted))
        body.addView(label("ACCOUNT BALANCE", 12f, muted))
        body.addView(label("—", 38f))
        body.addView(label("CURRENT FIXED WAGER  —", 13f, muted))
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val start = Button(this).apply {
            text = "START"
            isEnabled = false  // Never fake a live start without secure API verification.
            setAllCaps(false)
        }
        val stop = Button(this).apply {
            text = "STOP"
            isEnabled = false
            setAllCaps(false)
        }
        buttons.addView(start,LinearLayout.LayoutParams(0,dp(54),1f))
        buttons.addView(stop,LinearLayout.LayoutParams(0,dp(54),1f))
        body.addView(buttons)
        body.addView(label("TODAY", 16f))
        body.addView(label("WINS  —                      LOSSES  —", 16f))
        body.addView(label("NET P&L  —", 13f, muted))
        body.addView(label("MONEY MADE", 12f, green))
        body.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; progress=0
            progressTintList=android.content.res.ColorStateList.valueOf(green)
        }, LinearLayout.LayoutParams(-1,dp(12)))
        body.addView(label("MONEY LOST", 12f, red))
        body.addView(ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100; progress=0
            progressTintList=android.content.res.ColorStateList.valueOf(red)
        }, LinearLayout.LayoutParams(-1,dp(12)))
        body.addView(label("TRADE LOGS", 15f))
        body.addView(label("No connected trading records. This screen will show verified fills, settlements, fees and + / − P&L after private backend setup.",12f,muted))
        body.addView(label("No balance, result or trade is fabricated. Keep your Kalshi API key on the private server, never on the phone.",11f,muted))
    }

    override fun onStop() {
        // Sensitive data never stays visible in the app switcher or on return.
        if (unlocked) lockedScreen()
        super.onStop()
    }
}
