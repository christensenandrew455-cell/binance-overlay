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
import android.text.InputType
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

/** Fingerprint-locked controls; the Kalshi private key stays in Vercel. */
class KalshiTradingDashboardActivity:AppCompatActivity() {
    private val bg=Color.rgb(5,7,12)
    private val muted=Color.rgb(150,164,183)
    private val white=Color.rgb(237,241,248)
    private val green=Color.rgb(66,221,139)
    private val red=Color.rgb(239,94,106)
    private val handler=Handler(Looper.getMainLooper())
    private var unlocked=false
    private var prompting=false
    private lateinit var root:LinearLayout
    private lateinit var state:TextView
    private lateinit var balance:TextView
    private lateinit var wager:TextView
    private lateinit var counts:TextView
    private lateinit var net:TextView
    private lateinit var logs:TextView
    private lateinit var gainLabel:TextView
    private lateinit var lossLabel:TextView
    private lateinit var gainBar:ProgressBar
    private lateinit var lossBar:ProgressBar
    private lateinit var startButton:Button
    private lateinit var stopButton:Button
    private lateinit var resetButton:Button
    private val ledger by lazy {KalshiPhoneLedger(this)}
    private val permission=registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val exportPicker=registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) {uri ->
        if(uri!=null) Thread {
            val result=runCatching {
                contentResolver.openOutputStream(uri,"wt")?.bufferedWriter()?.use {
                    it.write(ledger.exportCsv())
                } ?: error("Could not write CSV")
            }
            runOnUiThread {
                Toast.makeText(this,if(result.isSuccess) "Full trade logs exported"
                    else "Export failed: ${result.exceptionOrNull()?.message}",Toast.LENGTH_LONG).show()
            }
        }.start()
    }
    private val refreshTimer=object:Runnable {
        override fun run() {
            if(unlocked){
                refresh()
                handler.postDelayed(this,3000)
            }
        }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
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
        locked()
        handler.postDelayed({if(!isFinishing) unlock()},350)
    }
    private fun dp(i:Int)=(i*resources.displayMetrics.density).toInt()
    private fun label(value:String,size:Float=16f,color:Int=white)=TextView(this).apply {
        text=value; textSize=size; setTextColor(color); gravity=Gravity.CENTER
        setPadding(dp(5),dp(6),dp(5),dp(6))
    }
    private fun button(value:String,onTap:()->Unit)=Button(this).apply {
        text=value;isAllCaps=false;setOnClickListener {onTap()}
    }
    private fun locked(message:String="Unlock Kalshi Trader with your fingerprint") {
        unlocked=false
        handler.removeCallbacks(refreshTimer)
        root.removeAllViews()
        root.gravity=Gravity.CENTER
        root.addView(label("KALSHI",28f))
        root.addView(label(message,14f,muted))
        root.addView(button("UNLOCK") {unlock()})
    }
    private fun unlock(){
        if(unlocked||prompting||isFinishing)return
        if(BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG)!=BiometricManager.BIOMETRIC_SUCCESS){
            locked("Enroll a strong fingerprint in Android Settings.")
            return
        }
        prompting=true
        BiometricPrompt(this,ContextCompat.getMainExecutor(this),
            object:BiometricPrompt.AuthenticationCallback(){
                override fun onAuthenticationSucceeded(result:BiometricPrompt.AuthenticationResult){
                    super.onAuthenticationSucceeded(result)
                    prompting=false;unlocked=true;dashboard()
                }
                override fun onAuthenticationError(code:Int,message:CharSequence){
                    super.onAuthenticationError(code,message)
                    prompting=false
                    if(!unlocked)locked("Fingerprint required. Tap UNLOCK.")
                }
            }).authenticate(BiometricPrompt.PromptInfo.Builder()
                .setTitle("Kalshi Trader")
                .setSubtitle("Verify your fingerprint")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel").build())
    }
    private fun command(action:String){
        try {
            val intent=Intent(this,KalshiPhoneTradingService::class.java).setAction(action)
            if(action==KalshiPhoneTradingService.ACTION_STOP) startService(intent)
            else ContextCompat.startForegroundService(this,intent)
            handler.postDelayed({if(unlocked)refresh()},900)
        }catch(e:Exception){
            Toast.makeText(this,"Service error: ${e.message}",Toast.LENGTH_LONG).show()
        }
    }
    private fun ownerAccessKey() {
        if(!unlocked || isFinishing) return
        val field=EditText(this).apply {
            hint="Permanent Vercel access key"
            isSingleLine=true
            inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSelectAllOnFocus(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Secure connection")
            .setMessage("Enter your permanent access key once. It will be encrypted on this phone and checked by Vercel on every request. This is NOT your Kalshi private key.")
            .setView(field)
            .setNegativeButton("CANCEL",null)
            .setPositiveButton("SAVE") {_,_->
                try {
                    KalshiPhoneAccess.save(this,field.text.toString())
                    Toast.makeText(this,"Permanent access key saved securely",Toast.LENGTH_SHORT).show()
                    refresh()
                    command(KalshiPhoneTradingService.ACTION_CONNECT)
                } catch(e:Exception) {
                    Toast.makeText(this,"Access key not saved: ${e.message}",Toast.LENGTH_LONG).show()
                } finally {
                    field.text?.clear()
                }
            }.show()
    }

    private fun dashboard(){
        if(!unlocked)return
        root.removeAllViews();root.gravity=Gravity.TOP
        val scroll=ScrollView(this).apply {isFillViewport=true}
        val content=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(5),dp(8),dp(5),dp(20))
        }
        scroll.addView(content);root.addView(scroll)
        content.addView(label("KALSHI TRADER",24f))
        state=label("CHECKING SECURE CONNECTION",13f,muted).also{content.addView(it)}
        content.addView(label("ACCOUNT BALANCE",12f,muted))
        balance=label("—",39f).also{content.addView(it)}
        wager=label("FIXED WAGER —",14f,muted).also{content.addView(it)}
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        startButton=button("START") {
            AlertDialog.Builder(this)
                .setTitle("Start live trading?")
                .setMessage("This places real Kalshi orders using your fixed 30% stake, Eastern-time limits, and 50% drawdown lock.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("START") {_,_->command(KalshiPhoneTradingService.ACTION_START)}
                .show()
        }
        stopButton=button("STOP") {command(KalshiPhoneTradingService.ACTION_STOP)}
        row.addView(startButton,LinearLayout.LayoutParams(0,dp(54),1f))
        row.addView(stopButton,LinearLayout.LayoutParams(0,dp(54),1f))
        content.addView(row)
        content.addView(button("CHANGE ACCESS KEY") { ownerAccessKey() })
        content.addView(button("CHECK VERCEL CONNECTION & BALANCE") {
            command(KalshiPhoneTradingService.ACTION_CONNECT)
        })
        resetButton=button("RESET LOCK AFTER REVIEW") {
            AlertDialog.Builder(this)
                .setTitle("Reset trading lock?")
                .setMessage("Only after reviewing your recent Kalshi orders and exported logs.")
                .setNegativeButton("CANCEL",null)
                .setPositiveButton("REVIEWED") {_,_->
                    try {ledger.resetAfterReview();refresh()}
                    catch(e:Exception){Toast.makeText(this,e.message,Toast.LENGTH_LONG).show()}
                }.show()
        }
        content.addView(resetButton)
        content.addView(label("TODAY · EASTERN TIME",17f))
        counts=label("WINS —    LOSSES —",18f).also{content.addView(it)}
        net=label("NET —",15f,muted).also{content.addView(it)}
        gainLabel=label("MONEY MADE —",13f,green).also{content.addView(it)}
        gainBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100;progressTintList=android.content.res.ColorStateList.valueOf(green)
        }
        content.addView(gainBar,LinearLayout.LayoutParams(-1,dp(13)))
        lossLabel=label("MONEY LOST —",13f,red).also{content.addView(it)}
        lossBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100;progressTintList=android.content.res.ColorStateList.valueOf(red)
        }
        content.addView(lossBar,LinearLayout.LayoutParams(-1,dp(13)))
        content.addView(label("TRADE LOGS",17f))
        content.addView(button("EXPORT ALL TRADES CSV") {exportPicker.launch("kalshi-trades.csv")})
        logs=label("—",11f,muted).apply {gravity=Gravity.START}.also{content.addView(it)}
        content.addView(label("Kalshi private key remains in Vercel. Your permanent access key is encrypted on this phone and checked on every request.",11f,muted))
        if(Build.VERSION.SDK_INT>=33&&ContextCompat.checkSelfPermission(
            this,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        refresh()
        handler.removeCallbacks(refreshTimer)
        handler.postDelayed(refreshTimer,3000)
        if (!KalshiPhoneAccess.hasCode(this)) {
            handler.postDelayed({ if(unlocked) ownerAccessKey() }, 400)
        }
    }

    private fun money(cents:Long):String="$"+BigDecimal(cents)
        .movePointLeft(2).setScale(2,RoundingMode.UNNECESSARY).toPlainString()
    private fun refresh(){
        if(!unlocked||!::balance.isInitialized)return
        val s=ledger.status()
        val recent=s.updated>0L && System.currentTimeMillis()-s.updated<60_000L
        state.text=when {
            !KalshiPhoneAccess.hasCode(this) -> "ACCESS KEY NEEDED · TAP CHANGE ACCESS KEY"
            s.locked -> "FORCE STOP · ${s.reason}"
            s.active && !recent -> "TRADING INTERRUPTED · DATA STALE"
            s.active -> "TRADING ACTIVE"
            s.reason=="VERCEL_SERVER_UPDATE_PENDING" ->
                "VERCEL UPDATE PENDING · SERVER STILL ON OLD VERSION"
            s.reason=="VERCEL_ACCESS_CONFIG_PENDING" ->
                "VERCEL NEEDS UPDATED ACCESS CODE DEPLOYMENT"
            s.reason=="ACCESS_CODE_REJECTED" ->
                "ACCESS KEY NOT ACCEPTED · CHECK SECURE CONNECTION"
            s.reason=="VERCEL_API_NOT_PUBLISHED" ->
                "VERCEL API NOT PUBLISHED"
            s.reason=="NETWORK_TIMEOUT" ->
                "NETWORK TIMEOUT · TRY CHECK CONNECTION"
            s.reason.startsWith("VERCEL_") ->
                "VERCEL CONNECTION FAILED · ${s.reason}"
            !recent -> "NOT CONNECTED · TAP CHECK CONNECTION"
            else -> "STOPPED · KALSHI BALANCE VERIFIED"
        }
        state.setTextColor(if(s.active&&recent)green else if(s.locked)red else muted)
        balance.text=if(recent) s.equity?.let{money(it)}?:"—" else "—"
        wager.text="FIXED WAGER  "+(if(s.wager==0L)"—" else money(s.wager))
        startButton.isEnabled=KalshiPhoneAccess.hasCode(this)&&recent&&!s.active&&!s.locked&&(s.equity?:0L)>=10000L
        stopButton.isEnabled=s.active
        resetButton.isEnabled=s.locked&&!s.active
        val t=ledger.today()
        counts.text="WINS ${t.wins}        LOSSES ${t.losses}"
        net.text="NET "+(if(t.net>=0)"+" else "-")+money(kotlin.math.abs(t.net))
        gainLabel.text="MONEY MADE  "+money(t.profit)
        lossLabel.text="MONEY LOST  "+money(t.loss)
        val total=t.profit+t.loss
        gainBar.progress=if(total>0)(t.profit*100/total).toInt() else 0
        lossBar.progress=if(total>0)(t.loss*100/total).toInt() else 0
        logs.text=ledger.recentEvents()
    }
    override fun onStop(){
        if(unlocked)locked()
        super.onStop()
    }
    override fun onDestroy(){
        handler.removeCallbacks(refreshTimer)
        super.onDestroy()
    }
}
