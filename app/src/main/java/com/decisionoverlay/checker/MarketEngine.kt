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

class MarketEngine(private val scope: CoroutineScope, private val onUpdate:(EngineSnapshot)->Unit, initialDntThreshold:Int=Scorer.DEFAULT_DNT_THRESHOLD) {
 private val http=OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(20,TimeUnit.SECONDS).build()
 private val client=BinanceClient(http); private val mutex=Mutex(); private val started=AtomicBoolean(false)
 private val logs=ArrayDeque<String>()
 private val candles=linkedMapOf("5m" to mutableListOf<Candle>(),"15m" to mutableListOf<Candle>(),"1h" to mutableListOf<Candle>())
 private val feeds=linkedMapOf("5m" to MutableFeed(),"15m" to MutableFeed(),"1h" to MutableFeed())
 @Volatile var symbol="BTCUSDT"; private set
 @Volatile private var dntThreshold=initialDntThreshold.coerceIn(Scorer.MIN_DNT_THRESHOLD,Scorer.MAX_DNT_THRESHOLD)
 private val jobs=mutableListOf<Job>(); private var lastVerdict:Verdict?=null
 fun start(raw:String) {
  if(!started.compareAndSet(false,true)){updateSymbol(raw);return}
  symbol=normalize(raw)?: "BTCUSDT"
  scope.launch(Dispatchers.IO) {
   mutex.withLock { logLocked("Started public Binance feed for $symbol"); refreshAllLocked() }
   poll("5m",FIVE); poll("15m",FIFTEEN); poll("1h",HOUR)
  }
 }
 fun updateSymbol(raw:String) { scope.launch(Dispatchers.IO) {
  val next=normalize(raw)
  if(next==null){mutex.withLock{logLocked("Rejected symbol '$raw'");publishLocked()};return@launch}
  if(next==symbol)return@launch
  symbol=next
  mutex.withLock {
   candles.values.forEach{it.clear()}
   feeds.values.forEach{it.state=ConnectionState.NOT_WORKING;it.detail="Reloading $next";it.updatedAt=null;it.lastError=null}
   lastVerdict=null;logLocked("Symbol changed to $next");refreshAllLocked()
  }
 } }
 fun setDntThreshold(value:Int){val next=value.coerceIn(Scorer.MIN_DNT_THRESHOLD,Scorer.MAX_DNT_THRESHOLD);if(next==dntThreshold)return;dntThreshold=next;scope.launch(Dispatchers.IO){mutex.withLock{logLocked("DNT threshold changed to ±$next");publishLocked()}}}
 fun refreshNow(){scope.launch(Dispatchers.IO){mutex.withLock{logLocked("Manual refresh");refreshAllLocked()}}}
 fun stop(){jobs.forEach{it.cancel()};jobs.clear();http.dispatcher.executorService.shutdown();http.connectionPool.evictAll()}
 private fun poll(interval:String,period:Long){jobs+=scope.launch(Dispatchers.IO){
  while(isActive){
   val wait=period-(System.currentTimeMillis()%period)
   delay(wait+2000L)
   if(isActive)mutex.withLock{logLocked("$interval candle closed — checking completed candle");fetchLocked(interval,"CANDLE CLOSE")}
  }
 }}
 private fun refreshAllLocked(){fetchLocked("5m","STARTUP");fetchLocked("15m","STARTUP");fetchLocked("1h","STARTUP")}
 private fun fetchLocked(interval:String,via:String){
  try{
   val result=client.fetchKlines(symbol,interval,150)
   result.notes.forEach{logLocked("$interval fallback: $it")}
   val closed=result.candles.filter{it.closed}
   candles.getValue(interval).apply{clear();addAll(closed)}
   feeds.getValue(interval).apply{state=ConnectionState.CONNECTED;detail="$via OK via ${result.host} (${closed.size} CLOSED candles)";updatedAt=System.currentTimeMillis();lastError=null}
   logLocked("$interval REST OK: ${closed.size} completed candles via ${result.host}")
  }catch(e:Exception){feeds.getValue(interval).apply{state=ConnectionState.NOT_WORKING;detail="Not Working";lastError=e.message};logLocked("$interval REST FAIL: "+e.message)}
  publishLocked()
 }
 private fun publishLocked(){
  val now=System.currentTimeMillis()
  age("5m",FIVE*2,now);age("15m",FIFTEEN*2,now);age("1h",HOUR*2,now)
  val five=Scorer.score5m(candles.getValue("5m"),feeds.getValue("5m").snapshot())
  val direction=five.score.compareTo(0)
  val fifteen=Scorer.score15m(candles.getValue("15m"),feeds.getValue("15m").snapshot(),direction)
  val hour=Scorer.score1h(candles.getValue("1h"),feeds.getValue("1h").snapshot(),direction)
  val decision=Scorer.decide(five,fifteen,hour,dntThreshold)
  if(decision.verdict!=lastVerdict){logLocked("Overall verdict ${decision.verdict}, score ${signed(decision.totalScore)} / ±$dntThreshold: "+decision.reason.take(180));lastVerdict=decision.verdict}
  onUpdate(EngineSnapshot(symbol,decision,logs.joinToString("\n")))
 }
 private fun age(i:String,max:Long,now:Long){val f=feeds.getValue(i);val u=f.updatedAt?:return;if(f.state==ConnectionState.CONNECTED&&now-u>max){f.state=ConnectionState.NOT_WORKING;f.detail="Stale data";f.lastError="No fresh $i candle inside ${max/1000}s"}}
 private fun logLocked(s:String){logs.addLast("${CLOCK.format(LocalTime.now())}  $s");while(logs.size>60)logs.removeFirst()}
 private fun normalize(raw:String):String?=raw.trim().uppercase(Locale.US).takeIf{SYMBOL.matches(it)}
 private class MutableFeed{var state=ConnectionState.NOT_WORKING;var detail="Waiting for first fetch";var updatedAt:Long?=null;var lastError:String?=null;fun snapshot()=FeedStatus(state,detail,updatedAt,lastError)}
 companion object{private const val FIVE=300000L;private const val FIFTEEN=900000L;private const val HOUR=3600000L;private val SYMBOL=Regex("^[A-Z0-9]{5,20}$");private val CLOCK=DateTimeFormatter.ofPattern("HH:mm:ss")}
}
