package com.decisionoverlay.checker
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
class OverlayService:Service(){
 private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate);private val main=Handler(Looper.getMainLooper());private lateinit var engine:MarketEngine;private var panel:OverlayPanel?=null;private var engineStarted=false;private var lastAlertedVerdict:Verdict?=null
 override fun onCreate(){super.onCreate();createChannel();val p=getSharedPreferences(PREFS,MODE_PRIVATE);engine=MarketEngine(scope,{s->main.post{render(s)}},p.getInt(KEY_STRICTNESS,1),p.getString(KEY_TARGET_PRICE,null)?.toDoubleOrNull());p.registerOnSharedPreferenceChangeListener(strictnessListener)}
 override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{if(intent?.action==ACTION_STOP){stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return START_NOT_STICKY};startInForeground("Starting target-aware BTC checker");val p=getSharedPreferences(PREFS,MODE_PRIVATE);val symbol=intent?.getStringExtra(EXTRA_SYMBOL)?:p.getString(KEY_SYMBOL,"BTCUSDT")?:"BTCUSDT";if(intent?.getBooleanExtra(EXTRA_SHOW_PANEL,false)==true)ensurePanel();if(!engineStarted){engineStarted=true;engine.start(symbol)}else if(intent?.hasExtra(EXTRA_SYMBOL)==true){engine.updateSymbol(symbol);engine.refreshNow()};return START_STICKY}
 private fun hidePanel(){val old=panel;panel=null;try{old?.detach()}catch(_:Exception){}}
 override fun onDestroy(){getSharedPreferences(PREFS,MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(strictnessListener);hidePanel();if(engineStarted)try{engine.stop()}catch(_:Exception){};scope.cancel();super.onDestroy()}
 override fun onBind(intent:Intent?):IBinder?=null
 private fun ensurePanel(){if(panel!=null)return;val wm=getSystemService(WINDOW_SERVICE) as android.view.WindowManager;panel=OverlayPanel(this,wm){hidePanel()};panel?.attach()}
 private fun render(s:EngineSnapshot){panel?.render(s);val v=s.decision.verdict;if(v!=lastAlertedVerdict)notifySignal(s);getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,buildNotification(s.symbol+"  "+v.name))}
 private fun notifySignal(s:EngineSnapshot){val v=s.decision.verdict;val title=when(v){Verdict.UP->"UP — ENTER NOW";Verdict.DOWN->"DOWN — ENTER NOW";Verdict.DNT->"DNT — No entry"};val body=when(v){Verdict.UP->"New UP entry window: 60 seconds. BTC "+"$"+fmtPrice(s.decision.currentPrice)+"; target "+"\$"+fmtPrice(s.decision.targetPrice)+".";Verdict.DOWN->"New DOWN entry window: 60 seconds. BTC "+"$"+fmtPrice(s.decision.currentPrice)+"; target "+"\$"+fmtPrice(s.decision.targetPrice)+".";Verdict.DNT->"No valid entry now. Wait for a fresh signal."};val open=PendingIntent.getActivity(this,1,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);val n=NotificationCompat.Builder(this,ALERT_CHANNEL_ID).setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).setOnlyAlertOnce(false).setPriority(NotificationCompat.PRIORITY_HIGH).build();getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID,n);lastAlertedVerdict=v}
 private fun startInForeground(t:String){val n=buildNotification(t);if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.UPSIDE_DOWN_CAKE)startForeground(NOTIFICATION_ID,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)else startForeground(NOTIFICATION_ID,n)}
 private fun buildNotification(t:String):Notification{val open=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);return NotificationCompat.Builder(this,CHANNEL_ID).setSmallIcon(R.drawable.ic_stat).setContentTitle("Trading").setContentText(t).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setPriority(NotificationCompat.PRIORITY_LOW).addAction(R.drawable.ic_stat,"STOP RUNNING",PendingIntent.getService(this,2,Intent(this,OverlayService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()}
 private fun createChannel(){val m=getSystemService(NotificationManager::class.java);m.createNotificationChannel(NotificationChannel(CHANNEL_ID,"Floating decision checker",NotificationManager.IMPORTANCE_LOW));val a=NotificationChannel(ALERT_CHANNEL_ID,"UP / DOWN / DNT trading alerts",NotificationManager.IMPORTANCE_HIGH);a.description="Alerts when a new UP or DOWN entry opens, or when the checker returns to DNT.";m.createNotificationChannel(a)}
 private val strictnessListener=android.content.SharedPreferences.OnSharedPreferenceChangeListener{p,k->if(k==KEY_STRICTNESS)engine.setStrictness(p.getInt(KEY_STRICTNESS,1))}
 companion object{const val EXTRA_SYMBOL="symbol";const val EXTRA_SHOW_PANEL="show_panel";const val ACTION_STOP="com.decisionoverlay.checker.STOP";const val PREFS="overlay_prefs";const val KEY_SYMBOL="symbol";const val KEY_DNT_THRESHOLD="dnt_threshold";const val KEY_STRICTNESS="strictness_level";const val KEY_TARGET_PRICE="kalshi_target_price";private const val CHANNEL_ID="decision_overlay";private const val ALERT_CHANNEL_ID="decision_signal_alerts_v2";private const val NOTIFICATION_ID=42;private const val ALERT_NOTIFICATION_ID=43}
}