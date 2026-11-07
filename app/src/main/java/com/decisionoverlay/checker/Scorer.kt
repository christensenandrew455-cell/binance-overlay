package com.decisionoverlay.checker

/** Weighted signal model: 1h ±4, 15m MACD ±2 + volume ±2, 5m EMA8/21 ±2. */
object Scorer {
 const val MIN_CANDLES=40
 const val DEFAULT_DNT_THRESHOLD=4
 const val MIN_DNT_THRESHOLD=2
 const val MAX_DNT_THRESHOLD=6

 fun score5m(candles:List<Candle>,feed:FeedStatus)=score(candles,feed,"5-minute","5m",::components5m)
 fun score15m(candles:List<Candle>,feed:FeedStatus)=score(candles,feed,"15-minute","15m",::components15m)
 fun score1h(candles:List<Candle>,feed:FeedStatus)=score(candles,feed,"1-hour","1h",::components1h)

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

 private fun components1h(data:List<Candle>,price:Double):List<ComponentScore>{
  val ema20=Indicators.ema(data.map{it.close},20);val rsi=Indicators.rsi(data.map{it.close})
  val distance=if(ema20==null||price==0.0)Double.POSITIVE_INFINITY else kotlin.math.abs(price-ema20)/price
  val neutral=distance<=0.0005||(rsi!=null&&rsi in 45.0..55.0)
  val points=when{neutral||ema20==null||rsi==null->0;price>ema20&&rsi>50.0->4;price<ema20&&rsi<50.0->-4;else->0}
  val desc=when(points){4->"Bullish trend (+4).";-4->"Bearish trend (-4).";else->"Neutral/mixed trend (0)."}
  return listOf(ComponentScore("1H trend (EMA20 + RSI)",points,"Price ${fmtNum(price)} vs EMA20 ${fmtNum(ema20)}; RSI ${fmtNum(rsi)}. $desc"))
 }

 private fun components15m(data:List<Candle>,price:Double):List<ComponentScore>{
  val macd=Indicators.macd(data.map{it.close})
  val macdPoints=when{macd==null->0;macd.line>macd.signal->2;macd.line<macd.signal->-2;else->0}
  val ratio=Indicators.volumeRatio(data)
  val volumePoints=when{ratio==null||ratio<=1.0->0;macdPoints>0->2;macdPoints<0->-2;else->0}
  val macdText=when(macdPoints){2->"bullish momentum (+2).";-2->"bearish momentum (-2).";else->"neutral/unavailable (0)."}
  val volumeText=when(volumePoints){2->"confirms bullish MACD (+2).";-2->"confirms bearish MACD (-2).";else->"no directional volume confirmation (0)."}
  return listOf(
   ComponentScore("15M MACD vs signal",macdPoints,"MACD ${fmtNum(macd?.line)} vs signal ${fmtNum(macd?.signal)}; $macdText"),
   ComponentScore("15M volume confirmation",volumePoints,"Latest volume is ${fmtNum(ratio)}x the previous 20-candle average; $volumeText")
  )
 }

 private fun components5m(data:List<Candle>,price:Double):List<ComponentScore>{
  val closes=data.map{it.close};val ema8=Indicators.ema(closes,8);val ema21=Indicators.ema(closes,21)
  val points=when{ema8==null||ema21==null->0;ema8>ema21->2;ema8<ema21->-2;else->0}
  val desc=when(points){2->"bullish short-term direction (+2).";-2->"bearish short-term direction (-2).";else->"neutral/unavailable (0)."}
  return listOf(ComponentScore("5M EMA 8 / EMA 21",points,"EMA8 ${fmtNum(ema8)} vs EMA21 ${fmtNum(ema21)}; $desc"))
 }

 private fun candleBias(data:List<Candle>):String{
  if(data.isEmpty())return "n/a"
  return if(data.last().close>=data.last().open)"last candle up" else "last candle down"
 }
}