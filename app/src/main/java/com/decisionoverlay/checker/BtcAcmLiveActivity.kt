package com.decisionoverlay.checker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONArray
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * ONE screen: current 15-minute market, no legacy backtests, no
 * 60-candle plot. The 60 completed candles are for ACM only.
 */
class BtcAcmLiveActivity : AppCompatActivity() {
    private val handler=Handler(Looper.getMainLooper())
    private lateinit var start:Button
    private lateinit var stop:Button
    private lateinit var status:TextView
    private lateinit var detail:TextView
    private lateinit var graph:BtcKalshiQuarterChart
    private val notificationPermission=registerForActivityResult(
        ActivityResultContracts.RequestPermission()) {}
    private val refresher=object:Runnable {
        override fun run() { refresh(); handler.postDelayed(this,1200L) }
    }

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(16,12,16,20)
            setBackgroundColor(Color.rgb(15,22,31))
        }
        fun label(s:String,size:Float):TextView=TextView(this).apply{
            text=s; textSize=size;setTextColor(Color.WHITE)
            setTextIsSelectable(true);setPadding(0,7,0,7)
        }
        root.addView(label("BTC / KALSHI — LIVE 15-MINUTE MARKET",19f))
        root.addView(label(
            "WHITE: BTC price since market start. YELLOW: Kalshi target. " +
            "GREEN/RED: the moving YES/NO buy boundaries where BTC's calibrated " +
            "historical odds may exceed Kalshi's actual ask plus fees. " +
            "Only the last 60 COMPLETE candles are used for ACM calculations; " +
            "they are not plotted. Dashed boundaries use the latest ACM and " +
            "current Kalshi asks, not future BTC prices.",12f))
        graph=BtcKalshiQuarterChart(this)
        root.addView(graph,LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            (resources.displayMetrics.density*370).toInt()))
        status=label("Press START to load probability calibration.",15f)
        root.addView(status)
        start=Button(this).apply {
            text="START BTC BUY NOTIFICATIONS"
            setOnClickListener{
                if(Build.VERSION.SDK_INT>=33 &&
                    ContextCompat.checkSelfPermission(this@BtcAcmLiveActivity,
                        Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                ContextCompat.startForegroundService(this@BtcAcmLiveActivity,
                    Intent(this@BtcAcmLiveActivity,
                        BtcAcmLiveService::class.java).setAction(BtcAcmLiveService.START))
                refresh()
            }
        }
        stop=Button(this).apply {
            text="STOP"
            setOnClickListener {
                startService(Intent(this@BtcAcmLiveActivity,
                    BtcAcmLiveService::class.java).setAction(BtcAcmLiveService.STOP))
            }
        }
        root.addView(start)
        root.addView(stop)
        detail=label("",13f)
        root.addView(detail)
        root.addView(Button(this).apply {
            text="COPY LIVE VALUES"
            setOnClickListener {
                val report=getSharedPreferences(BtcAcmLiveService.PREFS,MODE_PRIVATE)
                    .getString("report","").orEmpty()
                if(report.isNotBlank())
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("BTC/Kalshi live",report))
            }
        })
        setContentView(ScrollView(this).apply{addView(root)})
        refresh()
    }
    override fun onResume(){
        super.onResume()
        handler.removeCallbacks(refresher)
        handler.post(refresher)
    }
    override fun onPause(){
        handler.removeCallbacks(refresher)
        super.onPause()
    }
    private fun refresh(){
        if(!::status.isInitialized)return
        val p=getSharedPreferences(BtcAcmLiveService.PREFS,MODE_PRIVATE)
        val state=p.getString("phase","idle")
        status.text=p.getString("status","START monitoring to see live prices.")
        detail.text=p.getString("report","")
        start.isEnabled=state!="starting" && state!="running"
        stop.isEnabled=state=="starting" || state=="running"
        graph.load(p.getString("curve","").orEmpty(),
            p.getFloat("target",0f).toDouble())
    }
}

class BtcKalshiQuarterChart(context:android.content.Context):View(context){
    private data class Point(
        val at:Long,val up:Double?,val down:Double?,
        val btc:Double?,val future:Boolean
    )
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var data=emptyList<Point>()
    private var target=0.0
    private val density=resources.displayMetrics.density
    private val white=Color.rgb(230,240,248)
    private val green=Color.rgb(52,226,153)
    private val red=Color.rgb(253,109,115)
    private val yellow=Color.rgb(255,207,99)
    private val dim=Color.rgb(141,160,182)
    private fun dp(v:Float)=v*density

    fun load(raw:String,t:Double){
        data=try{
            val arr=JSONArray(raw)
            (0 until arr.length()).map { i->
                val row=arr.getJSONArray(i)
                Point(row.getLong(0),
                    if(row.isNull(1))null else row.getDouble(1),
                    if(row.isNull(2))null else row.getDouble(2),
                    if(row.isNull(3))null else row.getDouble(3),
                    row.getBoolean(4))
            }.sortedBy{it.at}
        }catch(_:Exception){emptyList()}
        target=t
        invalidate()
    }
    private fun write(c:Canvas,label:String,x:Float,y:Float,
        color:Int=white,size:Float=12f){
        paint.pathEffect=null
        paint.color=color
        paint.style=Paint.Style.FILL
        paint.textSize=dp(size)
        c.drawText(label,x,y,paint)
    }
    override fun onDraw(c:Canvas){
        super.onDraw(c)
        c.drawColor(Color.rgb(23,32,45))
        val l=dp(15f)
        val r=width-dp(14f)
        val top=dp(43f)
        val bot=height-dp(68f)
        write(c,"CURRENT 15-MINUTE MARKET — PRICE LINE",l,dp(24f),white,13f)
        if(data.size<2 || target<=0.0){
            write(c,"Waiting for market / history...",l,(top+bot)/2,dim,14f)
            return
        }
        val expiry=data.last().at
        val marketStart=expiry-15L*BtcAcmMath.MINUTE_MS
        if(expiry<=marketStart)return
        val prices=mutableListOf(target)
        for(p in data){
            p.btc?.let{prices.add(it)}
            p.up?.let{prices.add(it)}
            p.down?.let{prices.add(it)}
        }
        val lo=prices.minOrNull() ?: target
        val hi=prices.maxOrNull() ?: target
        val scale=max(hi-lo,1.0)
        val minP=lo-scale*0.16
        val maxP=hi+scale*0.16
        fun x(t:Long):Float=(l+(r-l)*
            ((t-marketStart).toDouble()/(expiry-marketStart)).toFloat())
        fun y(price:Double):Float=(bot-(price-minP)/(maxP-minP)*(bot-top)).toFloat()
        paint.pathEffect=null
        paint.strokeWidth=dp(0.7f)
        paint.color=Color.rgb(51,67,85)
        for(i in 1..4){
            val yy=top+(bot-top)*i/5f
            c.drawLine(l,yy,r,yy,paint)
        }
        paint.color=yellow
        paint.pathEffect=DashPathEffect(floatArrayOf(dp(4f),dp(4f)),0f)
        paint.strokeWidth=dp(1.3f)
        c.drawLine(l,y(target),r,y(target),paint)
        paint.pathEffect=null
        for(i in 0 until data.lastIndex){
            val a=data[i];val b=data[i+1]
            fun line(from:Double?,to:Double?,color:Int){
                if(from==null || to==null)return
                paint.color=color
                paint.strokeWidth=dp(1.8f)
                paint.pathEffect=if(b.future)
                    DashPathEffect(floatArrayOf(dp(5f),dp(4f)),0f)
                    else null
                c.drawLine(x(a.at),y(from),x(b.at),y(to),paint)
                paint.pathEffect=null
            }
            line(a.up,b.up,green)
            line(a.down,b.down,red)
            // Only completed close prices (plus live ticker) are drawn;
            // never connect a fabricated future price.
            line(a.btc,b.btc,white)
        }
        val observed=data.lastOrNull{it.btc!=null}
        if(observed!=null){
            paint.color=white
            paint.pathEffect=null
            paint.style=Paint.Style.FILL
            c.drawCircle(x(observed.at),y(observed.btc!!),dp(4f),paint)
            paint.color=dim
            paint.strokeWidth=dp(1f)
            paint.pathEffect=DashPathEffect(floatArrayOf(dp(3f),dp(4f)),0f)
            c.drawLine(x(observed.at),top,x(observed.at),bot,paint)
            paint.pathEffect=null
        }
        write(c,"START",l,bot+dp(22f),dim,12f)
        write(c,"END",r-dp(29f),bot+dp(22f),dim,12f)
        val timeLeft=if(observed==null)0L
            else max(0L,(expiry-observed.at)/1000)
        write(c,String.format(Locale.US,"NOW  %d:%02d LEFT",timeLeft/60,timeLeft%60),
            l,bot+dp(43f),white,12f)
        write(c,String.format(Locale.US,"TARGET $%,.2f",target),
            r-dp(147f),bot+dp(43f),yellow,11f)
        write(c,"ABOVE GREEN → YES BUY ZONE",l,top-dp(18f),green,11f)
        write(c,"BELOW RED → NO BUY ZONE",r-dp(171f),top-dp(18f),red,11f)
    }
}
