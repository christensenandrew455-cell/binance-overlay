package com.decisionoverlay.checker
import kotlin.math.abs
import kotlin.math.max
data class TargetEvaluation(val score:Int,val candidate:Verdict,val buffer:Double,val reason:String,val problems:List<String>)
object Scorer {
 const val MIN_CANDLES=40;const val DEFAULT_DNT_THRESHOLD=3;const val MIN_DNT_THRESHOLD=2;const val MAX_DNT_THRESHOLD=6
 fun score1m(c:List<Candle>,f:FeedStatus)=score(c,f,"1-minute","1m")
 fun score5m(c:List<Candle>,f:FeedStatus)=score(c,f,"5-minute","5m")
 fun score15m(c:List<Candle>,f:FeedStatus,fiveDirection:Int=0)=score(c,f,"15-minute","15m")
 fun timeframeBias(s:TimeframeScore):Verdict=if(s.connection!=ConnectionState.CONNECTED||s.candleCount<MIN_CANDLES)Verdict.DNT else when{s.score>0->Verdict.UP;s.score<0->Verdict.DOWN;else->Verdict.DNT}
 fun decide(one:TimeframeScore,five:TimeframeScore,fifteen:TimeframeScore,dntThreshold:Int=DEFAULT_DNT_THRESHOLD):Decision {
  val threshold=dntThreshold.coerceIn(MIN_DNT_THRESHOLD,MAX_DNT_THRESHOLD)
  val problems=listOf(one,five,fifteen).filter{it.connection!=ConnectionState.CONNECTED||it.candleCount<MIN_CANDLES}.map{"${it.label}: data unavailable."}
  val total=(one.score+five.score+fifteen.score).coerceIn(-10,10)
  val verdict=if(problems.isNotEmpty())Verdict.DNT else when{total>=threshold->Verdict.UP;total<=-threshold->Verdict.DOWN;else->Verdict.DNT}
  return Decision(verdict,"Compatibility decision",one,five,fifteen,problems,total,threshold)
 }
 fun evaluateTarget(one:List<Candle>,five:List<Candle>,fifteen:List<Candle>,feeds:List<FeedStatus>,target:Double?,price:Double?,threshold:Int=DEFAULT_DNT_THRESHOLD):TargetEvaluation {
  val problems=mutableListOf<String>();val labels=listOf("1-minute","5-minute","15-minute");val sets=listOf(one,five,fifteen)
  sets.forEachIndexed{i,x->if(x.count{it.closed}<MIN_CANDLES)problems+="${labels[i]}: need $MIN_CANDLES completed candles; received ${x.count{it.closed}}."}
  feeds.forEachIndexed{i,f->if(f.state!=ConnectionState.CONNECTED)problems+="${labels[i]} feed unavailable: ${f.lastError?:f.detail}."}
  if(target==null||!target.isFinite()||target<=0)problems+="Waiting for the active 15-minute target."
  if(price==null||!price.isFinite()||price<=0)problems+="Waiting for current BTC price."
  if(problems.isNotEmpty())return TargetEvaluation(0,Verdict.DNT,0.0,"DNT: required market data is not ready.",problems)
  val a=one.filter{it.closed}.sortedBy{it.openTime};val b=five.filter{it.closed}.sortedBy{it.openTime};val c=fifteen.filter{it.closed}.sortedBy{it.openTime}
  val p=price!!;val t=target!!;val closes=a.map{it.close};val ema=Indicators.ema(closes,9)
  val rise=closes.size>=3&&closes.takeLast(3).zipWithNext().all{it.second>it.first};val fall=closes.size>=3&&closes.takeLast(3).zipWithNext().all{it.second<it.first}
  val oneDir=when{ema!=null&&p>ema&&rise->1;ema!=null&&p<ema&&fall->-1;else->0}
  val old=b.takeLast(6).take(3);val recent=b.takeLast(3)
  val fiveUp=recent.size==3&&recent.maxOf{it.high}>old.maxOf{it.high}&&recent.minOf{it.low}>old.minOf{it.low}
  val fiveDown=recent.size==3&&recent.maxOf{it.high}<old.maxOf{it.high}&&recent.minOf{it.low}<old.minOf{it.low}
  val fiveDir=when{oneDir>0&&fiveUp->1;oneDir<0&&fiveDown->-1;else->0}
  val tail=c.takeLast(15);val ranges=tail.drop(1).mapIndexed{i,x->val prev=tail[i].close;max(x.high-x.low,max(abs(x.high-prev),abs(x.low-prev)))}
  val buffer=max(t*0.0005,(ranges.average().takeIf{it.isFinite()}?:0.0)*0.25)
  val last=c.takeLast(3).map{it.close};val towardUp=last.size==3&&last[2]>last[1]&&last[1]>=last[0];val towardDown=last.size==3&&last[2]<last[1]&&last[1]<=last[0]
  val targetPoints=when{p<t-buffer&&towardUp->2;p>t+buffer&&towardDown->-2;else->0}
  val score=oneDir*5+fiveDir*3+targetPoints
  val candidate=when{score>=threshold&&p<t-buffer->Verdict.UP;score<=-threshold&&p>t+buffer->Verdict.DOWN;else->Verdict.DNT}
  val why="1m ${oneDir*5}, 5m ${fiveDir*3}, target $targetPoints; buffer ${fmtPrice(buffer)}."
  val reason=when(candidate){Verdict.UP->"UP: bullish momentum below target. $why";Verdict.DOWN->"DOWN: bearish momentum above target. $why";Verdict.DNT->"DNT: score, direction, or target distance does not qualify. $why"}
  return TargetEvaluation(score.coerceIn(-10,10),candidate,buffer,reason,emptyList())
 }
 private fun score(c:List<Candle>,f:FeedStatus,label:String,interval:String):TimeframeScore {
  val d=c.filter{it.closed}.sortedBy{it.openTime};val closes=d.map{it.close};val p=closes.lastOrNull();val ema=if(p==null)null else Indicators.ema(closes,9);val rsi=if(p==null)null else Indicators.rsi(closes)
  val points=when{p==null||ema==null->0;p>ema->1;p<ema->-1;else->0}
  val parts=if(d.size<MIN_CANDLES)listOf(ComponentScore("Data",0,"Need $MIN_CANDLES completed candles, have ${d.size}."))else listOf(ComponentScore("EMA9 context",points,"Price ${fmtPrice(p)} vs EMA9 ${fmtPrice(ema)}."))
  val last=d.lastOrNull()
  return TimeframeScore(label,interval,points,parts,p,ema,null,rsi,null,null,null,if(last==null||last.close>=last.open)"last candle up" else "last candle down",Indicators.volumeRatio(d),f.state,f.detail,f.updatedAt,f.lastError,d.size)
 }
}