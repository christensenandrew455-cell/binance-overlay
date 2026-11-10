package com.decisionoverlay.checker
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
class MarketEngine(private val scope:CoroutineScope,private val onUpdate:(EngineSnapshot)->Unit,initialStrictnessLevel:Int=1){
 private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
 private val client=BinanceClient(http);private val mutex=Mutex();private val started=AtomicBoolean(false);private val logs=ArrayDeque<String>()
 private val candles=linkedMapOf("1m" to mutableListOf<Candle>(),"5m" to mutableListOf<Candle>(),"15m" to mutableListOf<Candle>())
 private val feeds=linkedMapOf("1m" to MutableFeed(),"5m" to MutableFeed(),"15m" to MutableFeed())
 @Volatile var symbol="BTCUSDT";private set
 @Volatile private var strictnessLevel=initialStrictnessLevel.coerceIn(0,4)
 private val activeProfile get()=StrictnessProfile.fromLevel(strictnessLevel)
 private val dntThreshold get()=activeProfile.scoreThreshold
 private val jobs=mutableListOf<Job>();private var targetPrice:Double?=null;private var targetSession=0L;private var candidate=Verdict.DNT;private var entryUntil=0L;private var lastVerdict=Verdict.DNT
 fun start(raw:String){if(!started.compareAndSet(false,true)){updateSymbol(raw);return};symbol=normalize(raw)?:"BTCUSDT"
  scope.launch(Dispatchers.IO){mutex.withLock{logLocked("Started target-aware Binance feed for $symbol");refreshAllLocked()};poll("1m",ONE);poll("5m",FIVE);poll("15m",FIFTEEN)}
  jobs+=scope.launch(Dispatchers.IO){while(isActive){delay(1000);mutex.withLock{publishLocked()}}}
 }
 fun updateSymbol(raw:String){scope.launch(Dispatchers.IO){val next=normalize(raw);if(next==null){mutex.withLock{logLocked("Rejected symbol '$raw'");publishLocked()};return@launch};if(next==symbol)return@launch;symbol=next
  mutex.withLock{candles.values.forEach{it.clear()};feeds.values.forEach{it.state=ConnectionState.NOT_WORKING;it.detail="Reloading $next";it.updatedAt=null;it.lastError=null};targetPrice=null;targetSession=0;candidate=Verdict.DNT;entryUntil=0;lastVerdict=Verdict.DNT;logLocked("Symbol changed to $next");refreshAllLocked()}
 }}
 fun setTargetPrice(value:Double?){scope.launch(Dispatchers.IO){mutex.withLock{targetPrice=value?.takeIf{it.isFinite()&&it>0.0};candidate=Verdict.DNT;entryUntil=0;logLocked(if(targetPrice==null)"Target price cleared" else "Target price set to ${fmtPrice(targetPrice)}");publishLocked()}}}
 fun setStrictness(level:Int){scope.launch(Dispatchers.IO){mutex.withLock{val next=level.coerceIn(0,4);if(next!=strictnessLevel){strictnessLevel=next;candidate=Verdict.DNT;entryUntil=0;logLocked("Strictness changed: ${activeProfile.label} (score ±${activeProfile.scoreThreshold}, ${activeProfile.requiredConfirmations} confirmations, volume ${activeProfile.volumeExpansionRatio}x)");publishLocked()}}}}
 fun refreshNow(){scope.launch(Dispatchers.IO){mutex.withLock{logLocked("Manual refresh");refreshAllLocked()}}}
 fun stop(){jobs.forEach{it.cancel()};jobs.clear();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
 private fun poll(i:String,period:Long){jobs+=scope.launch(Dispatchers.IO){while(isActive){delay(period-System.currentTimeMillis()%period+2000L);if(isActive)mutex.withLock{fetchLocked(i,"CANDLE CLOSE")}}}}
 private fun refreshAllLocked(){fetchLocked("1m","STARTUP");fetchLocked("5m","STARTUP");fetchLocked("15m","STARTUP")}
 private fun fetchLocked(i:String,via:String){try{val r=client.fetchKlines(symbol,i,150);r.notes.forEach{logLocked("$i fallback: $it")};val now=System.currentTimeMillis()

  val closed=r.candles.filter{it.closed};candles.getValue(i).apply{clear();addAll(closed)};feeds.getValue(i).apply{state=ConnectionState.CONNECTED;detail="$via OK via ${r.host} (${closed.size} closed candles)";updatedAt=now;lastError=null};logLocked("$i REST OK: ${closed.size} completed candles")
 }catch(e:Exception){feeds.getValue(i).apply{state=ConnectionState.NOT_WORKING;detail="Not Working";lastError=e.message};logLocked("$i REST FAIL: "+e.message)};publishLocked()}
 private fun publishLocked(){val now=System.currentTimeMillis();age("1m",ONE*2,now);age("5m",FIVE*2,now);age("15m",FIFTEEN*2,now)
  val oneRaw=Scorer.score1m(candles.getValue("1m"),feeds.getValue("1m").snapshot());val fiveRaw=Scorer.score5m(candles.getValue("5m"),feeds.getValue("5m").snapshot());val fifteenRaw=Scorer.score15m(candles.getValue("15m"),feeds.getValue("15m").snapshot())
  val start=now/FIFTEEN*FIFTEEN;val remaining=(start+FIFTEEN-now).coerceAtLeast(0L)
  val ev=Scorer.evaluatePullback(candles.getValue("1m"),feeds.getValue("1m").snapshot(),strictnessLevel,targetPrice)
  val one=oneRaw.copy(score=ev.score,components=listOf(ComponentScore("1m target score",ev.score,ev.reason)))
  val five=fiveRaw.copy(score=0,components=listOf(ComponentScore("Context only",0,"1-minute chart is the primary signal timeframe.")))
  val fifteen=fifteenRaw.copy(score=0,components=listOf(ComponentScore("Context only",0,"15-minute target is displayed for reference; it does not gate the 1-minute entry.")))
  var v=if(ev.problems.isNotEmpty())Verdict.DNT else ev.candidate
  if(v!=Verdict.DNT&&v!=candidate){candidate=v;entryUntil=now+60000L;logLocked("New $v target-aware 1m signal window opened for 60 seconds.")}
  if(v==Verdict.DNT){candidate=Verdict.DNT;entryUntil=0}
  if(v!=Verdict.DNT&&now>=entryUntil)v=Verdict.DNT
  val reason=if(ev.problems.isNotEmpty())"DNT: 1-minute market data unavailable." else if(v==Verdict.DNT&&candidate!=Verdict.DNT)"DNT: 60-second entry window expired." else ev.reason
  if(v!=lastVerdict){logLocked("Decision changed to $v (1m pullback score ${signed(ev.score)}).");lastVerdict=v}
  val d=Decision(v,reason,one,five,fifteen,ev.problems,ev.score,dntThreshold,targetPrice,one.price,remaining,(entryUntil-now).coerceAtLeast(0),ev.buffer)
  onUpdate(EngineSnapshot(symbol,d,logs.joinToString("\n")))
 }
 private fun age(i:String,max:Long,now:Long){val f=feeds.getValue(i);val u=f.updatedAt?:return;if(f.state==ConnectionState.CONNECTED&&now-u>max){f.state=ConnectionState.NOT_WORKING;f.detail="Stale data";f.lastError="No fresh $i candle inside ${max/1000}s"}}
 private fun logLocked(s:String){logs.addLast("${CLOCK.format(LocalTime.now())}  $s");while(logs.size>60)logs.removeFirst()}
 private fun normalize(s:String):String?=s.trim().uppercase(Locale.US).takeIf{SYMBOL.matches(it)}
 private class MutableFeed{var state=ConnectionState.NOT_WORKING;var detail="Waiting for first fetch";var updatedAt:Long?=null;var lastError:String?=null;fun snapshot()=FeedStatus(state,detail,updatedAt,lastError)}
 companion object{private const val ONE=60000L;private const val FIVE=300000L;private const val FIFTEEN=900000L;private val SYMBOL=Regex("^[A-Z0-9]{5,20}$");private val CLOCK=DateTimeFormatter.ofPattern("HH:mm:ss")}
}