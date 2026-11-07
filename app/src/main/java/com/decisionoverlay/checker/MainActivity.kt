package com.decisionoverlay.checker

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class MainActivity:AppCompatActivity(){
 private val prefs by lazy{getSharedPreferences(OverlayService.PREFS,MODE_PRIVATE)}
 private val screenScope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
 private lateinit var engine:MarketEngine;private var pendingSymbol:String?=null
 private val notifications=registerForActivityResult(ActivityResultContracts.RequestPermission()){pendingSymbol?.let(::launchOverlay);pendingSymbol=null}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContentView(R.layout.activity_main)
  findViewById<EditText>(R.id.symbolInput).setText(prefs.getString(OverlayService.KEY_SYMBOL,"BTCUSDT"))
  findViewById<Button>(R.id.permissionButton).setOnClickListener{openOverlaySettings()}
  findViewById<Button>(R.id.startButton).setOnClickListener{startOverlay()}
  findViewById<Button>(R.id.stopButton).setOnClickListener{stopService(Intent(this,OverlayService::class.java));setStatus("Floating decision stopped.")}
  findViewById<Button>(R.id.refreshButton).setOnClickListener{engine.refreshNow()}
  engine=MarketEngine(screenScope){s->runOnUiThread{render(s)}}
  engine.start(prefs.getString(OverlayService.KEY_SYMBOL,"BTCUSDT")?:"BTCUSDT")
  window.decorView.post{if(!Settings.canDrawOverlays(this))AlertDialog.Builder(this).setTitle("Allow the floating decision").setMessage("Android needs permission to show the small UP / DOWN / DNT badge over other apps.").setPositiveButton("Open settings"){_,_->openOverlaySettings()}.setNegativeButton("Not now",null).show()}
 }
 override fun onResume(){super.onResume();val ok=Settings.canDrawOverlays(this);findViewById<TextView>(R.id.permissionState).apply{text=if(ok)"FLOATING BADGE • READY" else "FLOATING BADGE • PERMISSION NEEDED";setTextColor(ContextCompat.getColor(this@MainActivity,if(ok)R.color.up else R.color.dnt))}}
 override fun onDestroy(){engine.stop();screenScope.cancel();super.onDestroy()}
 private fun render(s:EngineSnapshot){
  findViewById<TextView>(R.id.symbolHeading).text=s.symbol+" • LAST $"+fmtPrice(s.decision.five.price)
  bindChart(R.id.chart5,"5 MINUTE CHART",s.decision.five);bindChart(R.id.chart15,"15 MINUTE CHART",s.decision.fifteen);bindChart(R.id.chart1h,"1 HOUR CHART",s.decision.hour)
  findViewById<TextView>(R.id.problemsText).text=if(s.decision.problems.isEmpty())"No blockers. All three chart checks agree." else s.decision.problems.joinToString("\n\n"){"• "+it}
  findViewById<TextView>(R.id.logText).text=s.logText.ifBlank{"Waiting for first data update…"}
  val status=findViewById<TextView>(R.id.overallStatus);status.text="CURRENT DECISION: "+s.decision.verdict.name
  status.setTextColor(ContextCompat.getColor(this,when(s.decision.verdict){Verdict.UP->R.color.up;Verdict.DOWN->R.color.down;Verdict.DNT->R.color.dnt}))
 }
 private fun bindChart(id:Int,title:String,s:TimeframeScore){val bias=Scorer.timeframeBias(s);findViewById<TextView>(id).apply{
  text="$title     ${bias.name}  ${signed(s.score)}\nStatus: ${s.connection} • ${s.candleCount} closed candles\nUpdated: ${formatTime(s.updatedAt)}\n\n"+s.metricsText()
  setTextColor(ContextCompat.getColor(this@MainActivity,when(bias){Verdict.UP->R.color.up;Verdict.DOWN->R.color.down;Verdict.DNT->R.color.text}))
 }}
 private fun startOverlay(){val symbol=findViewById<EditText>(R.id.symbolInput).text?.toString()?.trim()?.uppercase().orEmpty()
  if(!symbol.matches(Regex("^[A-Z0-9]{5,20}$"))){setStatus("Enter a Binance.US spot symbol, e.g. BTCUSDT.");return}
  if(!Settings.canDrawOverlays(this)){setStatus("Allow floating badge permission, then tap Show on screen.");openOverlaySettings();return}
  if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU&&ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED){pendingSymbol=symbol;notifications.launch(Manifest.permission.POST_NOTIFICATIONS);return}
  launchOverlay(symbol)
 }
 private fun launchOverlay(symbol:String){prefs.edit().putString(OverlayService.KEY_SYMBOL,symbol).apply();ContextCompat.startForegroundService(this,Intent(this,OverlayService::class.java).putExtra(OverlayService.EXTRA_SYMBOL,symbol));setStatus("Small decision badge started for $symbol.")}
 private fun openOverlaySettings(){startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,Uri.parse("package:$packageName")))}
 private fun setStatus(s:String){findViewById<TextView>(R.id.statusText).text=s}
}
