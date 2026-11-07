package com.decisionoverlay.checker

import android.Manifest
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity:AppCompatActivity(){
 private val prefs by lazy{getSharedPreferences(OverlayService.PREFS,MODE_PRIVATE)}
 private val screenScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private lateinit var engine:MarketEngine
 private var checksStarted=false
 private var pendingSymbol:String?=null
 private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  val symbol=pendingSymbol;pendingSymbol=null
  if(granted&&symbol!=null)launchOverlay(symbol) else if(symbol!=null)setStatus("Notification permission is needed to keep the floating badge running.")
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window,true)
  setContentView(R.layout.activity_main)
  findViewById<EditText>(R.id.symbolInput).setText(prefs.getString(OverlayService.KEY_SYMBOL,"BTCUSDT"))
  findViewById<Button>(R.id.startCheckButton).setOnClickListener{startChecks()}
  findViewById<Button>(R.id.startButton).setOnClickListener{startOverlay()}
  findViewById<Button>(R.id.stopButton).setOnClickListener{stopService(Intent(this,OverlayService::class.java));setStatus("Floating badge stopped. Chart checks in this app are unchanged.")}
  findViewById<Button>(R.id.refreshButton).setOnClickListener{if(checksStarted)engine.refreshNow() else setStatus("Press START CHECKING first.")}
  setupCollapsibleCharts()
  engine=MarketEngine(screenScope){s->runOnUiThread{render(s)}}
  // Deliberately do not fetch on app launch. The user starts the checker manually.
 }
 override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig)}
 override fun onDestroy(){engine.stop();screenScope.cancel();super.onDestroy()}
 private fun setupCollapsibleCharts(){
  listOf(R.id.chart5Header to R.id.chart5Body,R.id.chart15Header to R.id.chart15Body,R.id.chart1hHeader to R.id.chart1hBody).forEach{(headerId,bodyId)->
   val body=findViewById<View>(bodyId);body.visibility=View.GONE
   findViewById<TextView>(headerId).setOnClickListener{
    val open=body.visibility!=View.VISIBLE
    body.visibility=if(open)View.VISIBLE else View.GONE
    (it as TextView).text=(if(open)"▾  " else "▸  ")+when(headerId){R.id.chart5Header->"5 MINUTE CHART";R.id.chart15Header->"15 MINUTE CHART";else->"1 HOUR CHART"}+chartSummary(bodyId)
   }
  }
 }
 private fun chartSummary(bodyId:Int):String{
  val text=findViewById<TextView>(bodyId).text.toString().lineSequence().firstOrNull().orEmpty()
  val match=Regex("(UP|DOWN|DNT)\\s+([+-]?\\d+)").find(text)
  return match?.let{"     ${it.groupValues[1]}  ${it.groupValues[2]}"}?:""
 }
 private fun startChecks(){
  val symbol=readSymbol()?:return
  prefs.edit().putString(OverlayService.KEY_SYMBOL,symbol).apply()
  if(!checksStarted){checksStarted=true;engine.start(symbol);setStatus("Checking $symbol now. Next checks run at each candle close.");findViewById<Button>(R.id.startCheckButton).text="CHECKING • RUNNING"}
  else{engine.updateSymbol(symbol);engine.refreshNow();setStatus("Refreshed $symbol. Automatic candle-close checks remain active.")}
 }
 private fun readSymbol():String?{
  val symbol=findViewById<EditText>(R.id.symbolInput).text?.toString()?.trim()?.uppercase().orEmpty()
  if(!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))){setStatus("Enter a Binance.US spot symbol, e.g. BTCUSDT.");return null}
  return symbol
 }
 private fun render(s:EngineSnapshot){
  findViewById<TextView>(R.id.symbolHeading).text=s.symbol+" • LAST $"+fmtPrice(s.decision.five.price)
  bindChart(R.id.chart5Body,R.id.chart5Header,"5 MINUTE CHART",s.decision.five)
  bindChart(R.id.chart15Body,R.id.chart15Header,"15 MINUTE CHART",s.decision.fifteen)
  bindChart(R.id.chart1hBody,R.id.chart1hHeader,"1 HOUR CHART",s.decision.hour)
  findViewById<TextView>(R.id.problemsText).text=if(s.decision.problems.isEmpty())"No blockers. All three chart checks agree." else s.decision.problems.joinToString("\n\n"){"• "+it}
  findViewById<TextView>(R.id.logText).text=s.logText.ifBlank{"Waiting for first data update…"}
  val status=findViewById<TextView>(R.id.overallStatus);status.text="CURRENT DECISION: "+s.decision.verdict.name
  status.setTextColor(ContextCompat.getColor(this,when(s.decision.verdict){Verdict.UP->R.color.up;Verdict.DOWN->R.color.down;Verdict.DNT->R.color.dnt}))
  updateHeader(R.id.chart5Header,"5 MINUTE CHART",s.decision.five)
  updateHeader(R.id.chart15Header,"15 MINUTE CHART",s.decision.fifteen)
  updateHeader(R.id.chart1hHeader,"1 HOUR CHART",s.decision.hour)
 }
 private fun bindChart(bodyId:Int,headerId:Int,title:String,s:TimeframeScore){
  val bias=Scorer.timeframeBias(s)
  findViewById<TextView>(bodyId).apply{
   text="$title  ${bias.name}  ${signed(s.score)}\nStatus: ${s.connection} • ${s.candleCount} closed candles\nUpdated: ${formatTime(s.updatedAt)}\n\n"+s.metricsText()
   setTextColor(ContextCompat.getColor(this@MainActivity,when(bias){Verdict.UP->R.color.up;Verdict.DOWN->R.color.down;Verdict.DNT->R.color.text}))
  }
 }
 private fun updateHeader(id:Int,title:String,s:TimeframeScore){
  val bias=Scorer.timeframeBias(s);val expanded=findViewById<View>(when(id){R.id.chart5Header->R.id.chart5Body;R.id.chart15Header->R.id.chart15Body;else->R.id.chart1hBody}).visibility==View.VISIBLE
  findViewById<TextView>(id).text=(if(expanded)"▾  " else "▸  ")+title+"     "+bias.name+"  "+signed(s.score)
 }
 private fun startOverlay(){
  val symbol=readSymbol()?:return
  if(!Settings.canDrawOverlays(this)){setStatus("Android needs permission for the floating badge. Opening permission settings.");startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));return}
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){pendingSymbol=symbol;notifications.launch(Manifest.permission.POST_NOTIFICATIONS);return}
  launchOverlay(symbol)
 }
 private fun launchOverlay(symbol:String){prefs.edit().putString(OverlayService.KEY_SYMBOL,symbol).apply();ContextCompat.startForegroundService(this,Intent(this,OverlayService::class.java).putExtra(OverlayService.EXTRA_SYMBOL,symbol));setStatus("Small floating decision started for $symbol.")}
 private fun setStatus(s:String){findViewById<TextView>(R.id.statusText).text=s}
}
