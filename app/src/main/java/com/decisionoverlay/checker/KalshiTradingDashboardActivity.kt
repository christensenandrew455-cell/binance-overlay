package com.decisionoverlay.checker

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import java.math.BigDecimal
import java.math.RoundingMode

/** Fingerprint-gated dashboard for the Android foreground trading worker. */
class KalshiTradingDashboardActivity : AppCompatActivity() {
    private val bg = Color.rgb(5,7,12)
    private val muted = Color.rgb(150,164,183)
    private val white = Color.rgb(237,241,248)
    private val green = Color.rgb(66,221,139)
    private val red = Color.rgb(239,94,106)
    private val handler=Handler(Looper.getMainLooper())
    private var unlocked=false
    private var promptVisible=false
    private var awaitingKeyFile=false
    private lateinit var root: LinearLayout
    private lateinit var state: TextView
    private lateinit var balance: TextView
    private lateinit var wager: TextView
    private lateinit var counts: TextView
    private lateinit var net: TextView
    private lateinit var logs: TextView
    private lateinit var gainLabel: TextView
    private lateinit var lossLabel: TextView
    private lateinit var gainBar: ProgressBar
    private lateinit var lossBar: ProgressBar
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resetButton: Button
    private val ledger by lazy { KalshiPhoneLedger(this) }

    private val permission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }

    private val exportPicker=registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if(uri!=null) Thread {
            val result=runCatching {
                val csv=ledger.exportCsv()
                contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {
                    it.write(csv)
                } ?: error("Cannot write selected file")
            }
            runOnUiThread {
                Toast.makeText(this,if(result.isSuccess) "Full trade CSV exported"
                    else "Export error: ${result.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    private val keyPicker=registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        awaitingKeyFile=false
        if(uri==null || !unlocked) return@registerForActivityResult
        val pem=try {
            contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use {
                val result=it.readText()
                if(result.isBlank() || result.length>20000) error("Private key file is too large or empty")
                result
            } ?: error("Could not read selected key")
        } catch(e: Exception) {
            Toast.makeText(this,"Couldn't read PEM file: ${e.message}",Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val input=EditText(this).apply {
            hint="Paste Kalshi API Key ID"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Connect Kalshi on this phone")
            .setMessage("Enter the API Key ID for the private PEM you selected. The key will be encrypted in Android Keystore; it won't be uploaded.")
            .setView(input)
            .setNegativeButton("Cancel",null)
            .setPositiveButton("SAVE KEY") { _,_ ->
                try {
                    KalshiPhoneCredentials.save(this,input.text.toString(),pem)
                    ledger.log("KEY_IMPORTED","Encrypted credentials saved on device")
                    Toast.makeText(this,"Key saved securely. Tap CHECK CONNECTION.",Toast.LENGTH_LONG).show()
                    refresh()
                } catch(e: Exception) {
                    Toast.makeText(this,"Key import failed: ${e.message}",Toast.LENGTH_LONG).show()
                }
            }.show()
    }

    private val refreshTimer=object: Runnable {
        override fun run() {
            if(unlocked) {
                refresh()
                handler.postDelayed(this,3000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        supportActionBar?.hide()
        root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            gravity=Gravity.CENTER
            setBackgroundColor(bg)
            setPadding(dp(20),dp(12),dp(20),dp(12))
        }
        setContentView(root)
        lockedScreen()
        handler.postDelayed({ if(!isFinishing) unlock() },350)
    }

    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=16f,color:Int=white)=TextView(this).apply {
        text=value
        textSize=size
        setTextColor(color)
        gravity=Gravity.CENTER
        setPadding(dp(5),dp(7),dp(5),dp(7))
    }
    private fun button(value:String,action:()->Unit)=Button(this).apply {
        text=value
        setAllCaps(false)
        setOnClickListener { action() }
    }
    private fun lockedScreen(message:String="Verify your fingerprint to open Kalshi Trader") {
        unlocked=false
        handler.removeCallbacks(refreshTimer)
        root.removeAllViews()
        root.gravity=Gravity.CENTER
        root.addView(label("KALSHI",27f))
        root.addView(label(message,14f,muted))
        root.addView(button("UNLOCK WITH FINGERPRINT") {unlock()})
    }
    private fun unlock() {
        if(unlocked || promptVisible || isFinishing)return
        if(BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS) {
            lockedScreen("Enroll a strong fingerprint in Android Settings.")
            return
        }
        promptVisible=true
        val prompt=BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object: BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    promptVisible=false
                    unlocked=true
                    dashboard()
                }
                override fun onAuthenticationError(code:Int,message:CharSequence) {
                    super.onAuthenticationError(code,message)
                    promptVisible=false
                    if(!unlocked)lockedScreen("Fingerprint required. Tap to try again.")
                }
            })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kalshi Trader")
            .setSubtitle("Verify with your fingerprint")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel").build())
    }

    private fun command(action:String) {
        val intent=Intent(this,KalshiPhoneTradingService::class.java).setAction(action)
        try {
            if(action==KalshiPhoneTradingService.ACTION_STOP) startService(intent)
            else ContextCompat.startForegroundService(this,intent)
            handler.postDelayed({if(unlocked)refresh()},800)
        } catch(e:Exception) {
            Toast.makeText(this,"Service error: ${e.message}",Toast.LENGTH_LONG).show()
        }
    }
    private fun importKey() {
        if(!unlocked)return
        awaitingKeyFile=true
        keyPicker.launch(arrayOf("application/x-pem-file","application/octet-stream","text/plain","*/*"))
    }
    private fun dashboard() {
        if(!unlocked)return
        root.removeAllViews()
        root.gravity=Gravity.TOP
        val scroll=ScrollView(this).apply {isFillViewport=true}
        val inner=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(5),dp(8),dp(5),dp(20))
        }
        scroll.addView(inner)
        root.addView(scroll)
        inner.addView(label("KALSHI TRADER",23f))
        state=label("CONNECTING...",13f,muted).also {inner.addView(it)}
        inner.addView(label("ACCOUNT BALANCE",13f,muted))
        balance=label("—",38f).also{inner.addView(it)}
        wager=label("FIXED WAGER —",15f,muted).also{inner.addView(it)}
        val controls=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER
        }
        startButton=button("START") {
            AlertDialog.Builder(this)
                .setTitle("Start live Kalshi trading?")
                .setMessage("This uses real money. The app enforces the 50→60¢ rule, time windows, balance limits, and the $600 cap.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("START") {_,_->
                    command(KalshiPhoneTradingService.ACTION_START)
                }.show()
        }
        stopButton=button("STOP") {command(KalshiPhoneTradingService.ACTION_STOP)}
        controls.addView(startButton,LinearLayout.LayoutParams(0,dp(54),1f))
        controls.addView(stopButton,LinearLayout.LayoutParams(0,dp(54),1f))
        inner.addView(controls)
        inner.addView(button("CHECK CONNECTION & REFRESH BALANCE") {
            command(KalshiPhoneTradingService.ACTION_CONNECT)
        })
        inner.addView(button("IMPORT PRIVATE KEY ON THIS PHONE") {importKey()})
        resetButton=button("RESET LOCK AFTER REVIEW") {
            AlertDialog.Builder(this)
                .setTitle("Reset risk lock?")
                .setMessage("Only reset after checking all trade logs and Kalshi orders. The next START will set a new balance anchor.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("I REVIEWED THE LOGS") {_,_->
                    try {ledger.resetAfterReview();refresh()}
                    catch(e:Exception) {
                        Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()
                    }
                }.show()
        }
        inner.addView(resetButton)
        inner.addView(label("TODAY · EASTERN TIME",16f))
        counts=label("WINS —     LOSSES —",17f).also{inner.addView(it)}
        net=label("NET —",15f,muted).also{inner.addView(it)}
        gainLabel=label("MONEY MADE  —",13f,green).also {inner.addView(it)}
        gainBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100
            progressTintList=android.content.res.ColorStateList.valueOf(green)
        }
        inner.addView(gainBar,LinearLayout.LayoutParams(-1,dp(14)))
        lossLabel=label("MONEY LOST  —",13f,red).also {inner.addView(it)}
        lossBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100
            progressTintList=android.content.res.ColorStateList.valueOf(red)
        }
        inner.addView(lossBar,LinearLayout.LayoutParams(-1,dp(14)))
        inner.addView(label("RECENT TRADE LOGS",15f))
        inner.addView(button("EXPORT ALL TRADES CSV") {exportPicker.launch("kalshi-all-trades.csv")})
        logs=label("—",11f,muted).apply{ gravity=Gravity.START }.also{inner.addView(it)}
        inner.addView(label("The background worker runs on this phone while Android allows the foreground service to run. API credentials never appear in logs.",11f,muted))
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(
            this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        refresh()
        handler.removeCallbacks(refreshTimer)
        handler.postDelayed(refreshTimer,3000)
    }

    private fun money(cents:Long):String =
        "$"+BigDecimal(cents).movePointLeft(2).setScale(2,RoundingMode.UNNECESSARY).toPlainString()
    private fun refresh() {
        if(!unlocked || !::balance.isInitialized)return
        val s=ledger.status()
        val hasKey=KalshiPhoneCredentials.exists(this)
        state.text=when {
            !hasKey -> "IMPORT YOUR API KEY TO CONNECT"
            s.locked -> "FORCE STOP · ${s.reason}"
            s.active && (s.updated==0L || System.currentTimeMillis()-s.updated > 60_000L) ->
                "TRADING INTERRUPTED · BALANCE DATA STALE"
            s.active -> "TRADING ACTIVE · PHONE BACKGROUND WORKER"
            s.equity==null -> "KEY SAVED · TAP CHECK CONNECTION"
            else -> "STOPPED · READY TO START"
        }
        state.setTextColor(if(s.active)green else if(s.locked)red else muted)
        balance.text=s.equity?.let {money(it)} ?: "—"
        wager.text="FIXED WAGER  "+(if(s.wager==0L)"—" else money(s.wager))
        startButton.isEnabled=hasKey && !s.active && !s.locked
        stopButton.isEnabled=s.active
        resetButton.isEnabled=s.locked && !s.active
        val t=ledger.today()
        counts.text="WINS ${t.wins}        LOSSES ${t.losses}"
        net.text="NET "+(if(t.net>=0) "+" else "-")+money(kotlin.math.abs(t.net))
        gainLabel.text="MONEY MADE  "+money(t.profit)
        lossLabel.text="MONEY LOST  "+money(t.loss)
        val total=t.profit+t.loss
        gainBar.progress=if(total>0)(t.profit*100/total).toInt() else 0
        lossBar.progress=if(total>0)(t.loss*100/total).toInt() else 0
        logs.text=ledger.recentEvents()
    }

    override fun onStop() {
        if(unlocked && !awaitingKeyFile)lockedScreen()
        super.onStop()
    }
    override fun onDestroy() {
        handler.removeCallbacks(refreshTimer)
        super.onDestroy()
    }
}
