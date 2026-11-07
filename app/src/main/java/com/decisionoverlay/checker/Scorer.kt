package com.decisionoverlay.checker

/** Weighted signal model: 1h ±4, 15m MACD ±2 + volume ±2, 5m EMA8/21 ±2. */
object Scorer {
 const val MIN_CANDLES=40
 const val DEFAULT_DNT_THRESHOLD=3
 const val MIN_DNT_THRESHOLD=2
 const val MAX_DNT_THRESHOLD=6

 fun score5m(candles:List<Candle>,feed:FeedStatus)=score(candles,feed,"5-minute","5m",::components5m)
 fun score15m(candles:List<Candle>,feed:FeedStatus,fiveDirection:Int=0)=score(candles,feed,"15-minute","15m"){data,price->components15m(data,price,fiveDirection)}
 fun score1h(candles:List<Candle>,feed:FeedStatus,fiveDirection:Int=0)=score(candles,feed,"1-hour","1h"){data,price->components1h(data,price,fiveDirection)}

 fun timeframeBias(score:TimeframeScore):Verdict {
  if(score.connection!=ConnectionState.CONNECTED||score.candleCount<MIN_CANDLES)return Verdict.DNT
  return when { score.score>0->Verdict.UP;score.score<0->Verdict.DOWN;else->Verdict.DNT }
 }

 fun decide(five:TimeframeScore,fifteen:TimeframeScore,hour:TimeframeScore,dntThreshold:Int=DEFAULT_DNT_THRESHOLD):Decision {
  val threshold=dntThreshold.coerceIn(MIN_DNT_THRESHOLD,MAX_DNT_THRESHOLD)
  val problems=mutableListOf<String>()
  val all=listOf(five,fifteen,hour)
  all.filter{it.candleCount<MIN_CANDLES}.forEach{problems+="${it.label}: need at least $MIN_CANDLES completed candles; received ${it.candleCount}."}
  all.filter{it.connection!=ConnectionState.CONNECTED}.forEach{
   problems+="${it.label}: Binance feed is not connected (${it.connectionDetail})."
   if(!it.lastError.isNullOrBlank())problems+="${it.label}: ${it.lastError}"
  }
  val total=(hour.score+fifteen.score+five.score).coerceIn(-10,10)
  val verdict=when { problems.isNotEmpty()->Verdict.DNT;total>=threshold->Verdict.UP;total<=-threshold->Verdict.DOWN;else->Verdict.DNT }
  val reason=when(verdict){
   Verdict.UP->"UP: combined score ${signed(total)} reaches the +$threshold threshold."
   Verdict.DOWN->"DOWN: combined score ${signed(total)} reaches the -$threshold threshold."
   Verdict.DNT->if(problems.isNotEmpty())"DNT: market data is incomplete or unavailable." else "DNT: combined score ${signed(total)} is inside the ±$threshold neutral zone."
  }
  return Decision(verdict,reason,five,fifteen,hour,problems,total,threshold)
 }

 private fun score(candles:List<Candle>,feed:FeedStatus,label:String,interval:String,componentsFor:(List<Candle>,Double)->List<ComponentScore>):TimeframeScore {
  val data=candles.filter{it.closed}.sortedBy{it.openTime}
  val closes=data.map{it.close};val price=closes.lastOrNull()
  val fast=when(interval){ "1h"->if(price==null)null else Indicators.ema(closes,20); "5m"->if(price==null)null else Indicators.ema(closes,8);else->null }
  val slow=if(interval=="5m"&&price!=null)Indicators.ema(closes,21)else null
  val rsi=if(price==null)null else Indicators.rsi(closes)
  val macd=if(price==null)null else Indicators.macd(closes)
  val components=if(data.size<MIN_CANDLES||price==null)listOf(ComponentScore("Data",0,"Need $MIN_CANDLES completed candles, have ${data.size}."))else componentsFor(data,price)
  return TimeframeScore(label,interval,components.sumOf{it.points},components,price,fast,slow,rsi,macd?.line,macd?.signal,macd?.histogram,candleBias(data),Indicators.volumeRatio(data),feed.state,feed.detail,feed.updatedAt,feed.lastError,data.size)
 }

 private fun components1h(data:List<Candle>,price:Double,direction:Int):List<ComponentScore>{
  val ema20=Indicators.ema(data.map{it.close},20);val rsi=Indicators.rsi(data.map{it.close})
  val distance=if(ema20==null||price==0.0)Double.POSITIVE_INFINITY else kotlin.math.abs(price-ema20)/price
  val neutral=distance<=0.001||(rsi!=null&&rsi in 45.0..55.0)
  val bias=when{neutral||ema20==null||rsi==null->0;price>ema20&&rsi>55.0->1;price<ema20&&rsi<45.0->-1;else->0}
  val points=when{direction==0||bias==0->0;direction==bias->1;else->-1}
  val desc=when(points){1->"Supports 5m direction (+1).";-1->"Opposes 5m direction (-1).";else->"Ranging/mixed (0)."}
  return listOf(ComponentScore("1H weak directional filter",points,"Price ${fmtNum(price)} vs EMA20 ${fmtNum(ema20)}; RSI ${fmtNum(rsi)}. $desc"))
 }

 private fun components15m(data:List<Candle>,price:Double,direction:Int):List<ComponentScore>{
  val structure=recentStructure(data)
  val points=when{direction>0&&structure>0->3;direction<0&&structure<0->-3;else->0}
  val desc=when(structure){1->"higher highs and higher lows";-1->"lower highs and lower lows";else->"mixed/ranging structure"}
  val detail=when{points>0->"15m $desc confirms 5m UP (+3).";points<0->"15m $desc confirms 5m DOWN (-3).";else->"15m $desc does not confirm 5m direction (0)."}
  return listOf(ComponentScore("15M structure confirmation",points,detail))
 }

 private fun recentStructure(data:List<Candle>):Int{
  if(data.size<6)return 0
  val old=data.takeLast(6).take(3);val recent=data.takeLast(3)
  val hh=recent.maxOf{it.high}>old.maxOf{it.high};val hl=recent.minOf{it.low}>old.minOf{it.low}
  val lh=recent.maxOf{it.high}<old.maxOf{it.high};val ll=recent.minOf{it.low}<old.minOf{it.low}
  return when{hh&&hl->1;lh&&ll->-1;else->0}
 }

 private fun recentMacdCross(closes:List<Double>):Int? {
  if(closes.size<40)return null
  val hist=mutableListOf<Double>()
  for(end in 35..closes.lastIndex){val m=Indicators.macd(closes.take(end+1))?:continue;hist.add(m.histogram)}
  for(i in hist.lastIndex downTo maxOf(1,hist.lastIndex-3)){
   if(hist[i-1]<=0.0&&hist[i]>0.0)return 1
   if(hist[i-1]>=0.0&&hist[i]<0.0)return -1
  }
  return null
 }

 private fun components5m(data:List<Candle>,price:Double):List<ComponentScore>{
  val closes=data.map{it.close};val ema8=Indicators.ema(closes,8);val cross=recentMacdCross(closes)
  val points=when{ema8==null||cross==null->0;price>ema8&&cross>0->6;price<ema8&&cross<0->-6;else->0}
  val desc=when(points){6->"price above EMA8 and fresh bullish MACD crossover (+6).";-6->"price below EMA8 and fresh bearish MACD crossover (-6).";else->"no aligned MACD crossover in latest 3 completed candles (0)."}
  return listOf(ComponentScore("5M immediate trigger",points,"EMA8 "+fmtNum(ema8)+"; "+desc))
 }

 private fun candleBias(data:List<Candle>):String{
  if(data.isEmpty())return "n/a"
  return if(data.last().close>=data.last().open)"last candle up" else "last candle down"
 }
}