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
import android.widget.SeekBar
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
 private var pendingShowOverlay=false
 private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted->
  val symbol=pendingSymbol;pendingSymbol=null
  if(granted&&symbol!=null)launchService(symbol,pendingShowOverlay) else if(symbol!=null)setStatus("Notification permission is needed for background status and UP / DOWN alerts.")
 }
 override fun onCreate(savedInstanceState:Bundle?){
  super.onCreate(savedInstanceState)
  WindowCompat.setDecorFitsSystemWindows(window,true)
  setContentView(R.layout.activity_main)
  findViewById<EditText>(R.id.symbolInput).setText(prefs.getString(OverlayService.KEY_SYMBOL,"BTCUSDT"))
  findViewById<Button>(R.id.startCheckButton).setOnClickListener{startChecks()}
  findViewById<Button>(R.id.startButton).setOnClickListener{startOverlay()}
  findViewById<Button>(R.id.stopButton).setOnClickListener{stopService(Intent(this,OverlayService::class.java));if(checksStarted){engine.stop();checksStarted=false;findViewById<Button>(R.id.startCheckButton).text="START CHECKING"};setStatus("Trading checker stopped. Background checks and alerts are off.")}
  findViewById<Button>(R.id.refreshButton).setOnClickListener{if(checksStarted)engine.refreshNow() else setStatus("Press START CHECKING first.")}
  setupCollapsibleCharts()
  engine=MarketEngine(screenScope,{s->runOnUiThread{render(s)}},prefs.getInt(OverlayService.KEY_DNT_THRESHOLD,Scorer.DEFAULT_DNT_THRESHOLD))
  setupThresholdControl()
  // Deliberately do not fetch on app launch. The user starts the checker manually.
 }
 override fun onConfigurationChanged(newConfig:Configuration){super.onConfigurationChanged(newConfig)}
 override fun onDestroy(){engine.stop();screenScope.cancel();super.onDestroy()}
 private fun setupThresholdControl(){
  val seek=findViewById<SeekBar>(R.id.thresholdSeek)
  val label=findViewById<TextView>(R.id.thresholdLabel)
  val initial=prefs.getInt(OverlayService.KEY_DNT_THRESHOLD,Scorer.DEFAULT_DNT_THRESHOLD).coerceIn(2,6)
  seek.progress=initial-2
  label.text="DNT THRESHOLD: ±$initial"
  seek.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
   override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean){
    val threshold=progress+2
    label.text="DNT THRESHOLD: ±$threshold"
    if(fromUser){prefs.edit().putInt(OverlayService.KEY_DNT_THRESHOLD,threshold).apply();engine.setDntThreshold(threshold)}
   }
   override fun onStartTrackingTouch(bar:SeekBar?){}
   override fun onStopTrackingTouch(bar:SeekBar?){}
  })
 }
 private fun setupCollapsibleCharts(){
  listOf(R.id.chart1mHeader to R.id.chart1mBody,R.id.chart5Header to R.id.chart5Body,R.id.chart15Header to R.id.chart15Body).forEach{(headerId,bodyId)->
   val body=findViewById<View>(bodyId);body.visibility=View.GONE
   findViewById<TextView>(headerId).setOnClickListener{
    val open=body.visibility!=View.VISIBLE
    body.visibility=if(open)View.VISIBLE else View.GONE
    (it as TextView).text=(if(open)"▾  " else "▸  ")+when(headerId){R.id.chart1mHeader->"1 MINUTE CHART";R.id.chart5Header->"5 MINUTE CHART";else->"15 MINUTE CHART"}+chartSummary(bodyId)
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
  else{if(symbol!=engine.symbol)engine.updateSymbol(symbol) else engine.refreshNow();setStatus("Refreshed $symbol. Automatic candle-close checks remain active.")}
  requestAndLaunchService(symbol,false)
 }
 private fun readSymbol():String?{
  val symbol=findViewById<EditText>(R.id.symbolInput).text?.toString()?.trim()?.uppercase().orEmpty()
  if(!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))){setStatus("Enter a Binance.US spot symbol, e.g. BTCUSDT.");return null}
  return symbol
 }
 private fun render(s:EngineSnapshot){
  findViewById<TextView>(R.id.symbolHeading).text=s.symbol+" • LAST $"+fmtPrice(s.decision.five.price)
  bindChart(R.id.chart1mBody,R.id.chart1mHeader,"1 MINUTE CHART",s.decision.one)
  bindChart(R.id.chart5Body,R.id.chart5Header,"5 MINUTE CHART",s.decision.five)
  bindChart(R.id.chart15Body,R.id.chart15Header,"15 MINUTE CHART",s.decision.fifteen)
  findViewById<TextView>(R.id.problemsText).text=if(s.decision.problems.isEmpty())"No data blockers. Decision is based on the combined weighted score." else s.decision.problems.joinToString("\n\n"){"• "+it}
  findViewById<TextView>(R.id.logText).text=s.logText.ifBlank{"Waiting for first data update…"}
  val status=findViewById<TextView>(R.id.overallStatus);status.text="CURRENT DECISION: "+s.decision.verdict.name+"   |   SCORE "+signed(s.decision.totalScore)+" / +10   |   DNT ±"+s.decision.dntThreshold
  status.setTextColor(ContextCompat.getColor(this,when(s.decision.verdict){Verdict.UP->R.color.up;Verdict.DOWN->R.color.down;Verdict.DNT->R.color.dnt}))
  updateHeader(R.id.chart1mHeader,"1 MINUTE CHART",s.decision.one)
  updateHeader(R.id.chart5Header,"5 MINUTE CHART",s.decision.five)
  updateHeader(R.id.chart15Header,"15 MINUTE CHART",s.decision.fifteen)
 }
 private fun bindChart(bodyId:Int,headerId:Int,title:String,s:TimeframeScore){
  val bias=Scorer.timeframeBias(s)
  findViewById<TextView>(bodyId).apply{
   text="$title  ${bias.name}  ${signed(s.score)}\nStatus: ${s.connection} • ${s.candleCount} closed candles\nUpdated: ${formatTime(s.updatedAt)}\n\n"+s.metricsText()
   setTextColor(ContextCompat.getColor(this@MainActivity,when(bias){Verdict.UP->R.color.up;Verdict.DOWN->R.color.down;Verdict.DNT->R.color.text}))
  }
 }
 private fun updateHeader(id:Int,title:String,s:TimeframeScore){
  val bias=Scorer.timeframeBias(s);val expanded=findViewById<View>(when(id){R.id.chart1mHeader->R.id.chart1mBody;R.id.chart5Header->R.id.chart5Body;else->R.id.chart15Body}).visibility==View.VISIBLE
  findViewById<TextView>(id).text=(if(expanded)"▾  " else "▸  ")+title+"     "+bias.name+"  "+signed(s.score)
 }
 private fun startOverlay(){
  val symbol=readSymbol()?:return
  if(!Settings.canDrawOverlays(this)){setStatus("Android needs permission for the floating badge. Opening permission settings.");startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")));return}
  requestAndLaunchService(symbol,true)
 }
 private fun requestAndLaunchService(symbol:String,showOverlay:Boolean){
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){pendingSymbol=symbol;pendingShowOverlay=showOverlay;notifications.launch(Manifest.permission.POST_NOTIFICATIONS);return}
  launchService(symbol,showOverlay)
 }
 private fun launchService(symbol:String,showOverlay:Boolean){
  prefs.edit().putString(OverlayService.KEY_SYMBOL,symbol).apply()
  val intent=Intent(this,OverlayService::class.java).putExtra(OverlayService.EXTRA_SYMBOL,symbol).putExtra(OverlayService.EXTRA_SHOW_PANEL,showOverlay)
  ContextCompat.startForegroundService(this,intent)
  setStatus(if(showOverlay)"Floating decision and background checker running for $symbol." else "Background checker running for $symbol. You can close the app; use STOP RUNNING to turn it off.")
 }
 private fun setStatus(s:String){findViewById<TextView>(R.id.statusText).text=s}
}
