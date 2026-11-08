package com.decisionoverlay.checker

/** Three-timeframe model: 1m early momentum ±2, 5m primary trigger ±6, 15m structure confirmation ±2. */
object Scorer {
 const val MIN_CANDLES=40
 const val DEFAULT_DNT_THRESHOLD=3
 const val MIN_DNT_THRESHOLD=2
 const val MAX_DNT_THRESHOLD=6

 fun score1m(candles:List<Candle>,feed:FeedStatus)=score(candles,feed,"1-minute","1m",::components1m)
 fun score5m(candles:List<Candle>,feed:FeedStatus)=score(candles,feed,"5-minute","5m",::components5m)
 fun score15m(candles:List<Candle>,feed:FeedStatus,fiveDirection:Int=0)=score(candles,feed,"15-minute","15m"){data,price->components15m(data,price,fiveDirection)}

 fun timeframeBias(score:TimeframeScore):Verdict {
  if(score.connection!=ConnectionState.CONNECTED||score.candleCount<MIN_CANDLES)return Verdict.DNT
  return when { score.score>0->Verdict.UP;score.score<0->Verdict.DOWN;else->Verdict.DNT }
 }

 fun decide(one:TimeframeScore,five:TimeframeScore,fifteen:TimeframeScore,dntThreshold:Int=DEFAULT_DNT_THRESHOLD):Decision {
  val threshold=dntThreshold.coerceIn(MIN_DNT_THRESHOLD,MAX_DNT_THRESHOLD)
  val problems=mutableListOf<String>()
  val all=listOf(one,five,fifteen)
  all.filter{it.candleCount<MIN_CANDLES}.forEach{problems+="${it.label}: need at least $MIN_CANDLES completed candles; received ${it.candleCount}."}
  all.filter{it.connection!=ConnectionState.CONNECTED}.forEach{
   problems+="${it.label}: Binance feed is not connected (${it.connectionDetail})."
   if(!it.lastError.isNullOrBlank())problems+="${it.label}: ${it.lastError}"
  }
  val total=(one.score+five.score+fifteen.score).coerceIn(-10,10)
  val verdict=when { problems.isNotEmpty()->Verdict.DNT;total>=threshold->Verdict.UP;total<=-threshold->Verdict.DOWN;else->Verdict.DNT }
  val reason=when(verdict){
   Verdict.UP->"UP: combined score ${signed(total)} reaches the +$threshold threshold."
   Verdict.DOWN->"DOWN: combined score ${signed(total)} reaches the -$threshold threshold."
   Verdict.DNT->if(problems.isNotEmpty())"DNT: market data is incomplete or unavailable." else "DNT: combined score ${signed(total)} is inside the ±$threshold neutral zone."
  }
  return Decision(verdict,reason,one,five,fifteen,problems,total,threshold)
 }

 private fun score(candles:List<Candle>,feed:FeedStatus,label:String,interval:String,componentsFor:(List<Candle>,Double)->List<ComponentScore>):TimeframeScore {
  val data=candles.filter{it.closed}.sortedBy{it.openTime}
  val closes=data.map{it.close};val price=closes.lastOrNull()
  val fast=if(price==null)null else Indicators.ema(closes,if(interval=="15m")21 else 8)
  val slow=if(interval=="5m"&&price!=null)Indicators.ema(closes,21)else null
  val rsi=if(price==null)null else Indicators.rsi(closes)
  val macd=if(price==null)null else Indicators.macd(closes)
  val components=if(data.size<MIN_CANDLES||price==null)listOf(ComponentScore("Data",0,"Need $MIN_CANDLES completed candles, have ${data.size}."))else componentsFor(data,price)
  return TimeframeScore(label,interval,components.sumOf{it.points},components,price,fast,slow,rsi,macd?.line,macd?.signal,macd?.histogram,candleBias(data),Indicators.volumeRatio(data),feed.state,feed.detail,feed.updatedAt,feed.lastError,data.size)
 }

 private fun components1m(data:List<Candle>,price:Double):List<ComponentScore>{
  val closes=data.map{it.close};val ema8=Indicators.ema(closes,8);val cross=recentMacdCross(closes,2)
  val points=when{ema8==null||cross==null->0;price>ema8&&cross>0->2;price<ema8&&cross<0->-2;else->0}
  val desc=when(points){2->"price above EMA8 with fresh bullish MACD crossover (+2).";-2->"price below EMA8 with fresh bearish MACD crossover (-2).";else->"no aligned fresh 1m momentum crossover (0)."}
  return listOf(ComponentScore("1M early momentum",points,"EMA8 "+fmtNum(ema8)+"; "+desc))
 }
 private fun components5m(data:List<Candle>,price:Double):List<ComponentScore>{
  val closes=data.map{it.close};val ema8=Indicators.ema(closes,8);val cross=recentMacdCross(closes,3)
  val points=when{ema8==null||cross==null->0;price>ema8&&cross>0->6;price<ema8&&cross<0->-6;else->0}
  val desc=when(points){6->"price above EMA8 and fresh bullish MACD crossover (+6).";-6->"price below EMA8 and fresh bearish MACD crossover (-6).";else->"no aligned MACD crossover in latest 3 completed candles (0)."}
  return listOf(ComponentScore("5M primary trigger",points,"EMA8 "+fmtNum(ema8)+"; "+desc))
 }
 private fun components15m(data:List<Candle>,price:Double,direction:Int):List<ComponentScore>{
  val structure=recentStructure(data)
  val points=when{direction>0&&structure>0->2;direction<0&&structure<0->-2;else->0}
  val desc=when(structure){1->"higher highs and higher lows";-1->"lower highs and lower lows";else->"mixed/ranging structure"}
  val detail=when{points>0->"15m $desc confirms 5m UP (+2).";points<0->"15m $desc confirms 5m DOWN (-2).";else->"15m $desc does not confirm 5m direction (0)."}
  return listOf(ComponentScore("15M structure confirmation",points,detail))
 }
 private fun recentStructure(data:List<Candle>):Int{
  if(data.size<6)return 0
  val old=data.takeLast(6).take(3);val recent=data.takeLast(3)
  val hh=recent.maxOf{it.high}>old.maxOf{it.high};val hl=recent.minOf{it.low}>old.minOf{it.low}
  val lh=recent.maxOf{it.high}<old.maxOf{it.high};val ll=recent.minOf{it.low}<old.minOf{it.low}
  return when{hh&&hl->1;lh&&ll->-1;else->0}
 }
 private fun recentMacdCross(closes:List<Double>,lookback:Int):Int? {
  if(closes.size<40)return null
  val hist=mutableListOf<Double>()
  for(end in 35..closes.lastIndex){val m=Indicators.macd(closes.take(end+1))?:continue;hist.add(m.histogram)}
  for(i in hist.lastIndex downTo maxOf(1,hist.lastIndex-lookback)){
   if(hist[i-1]<=0.0&&hist[i]>0.0)return 1
   if(hist[i-1]>=0.0&&hist[i]<0.0)return -1
  }
  return null
 }
 private fun candleBias(data:List<Candle>):String{
  if(data.isEmpty())return "n/a"
  return if(data.last().close>=data.last().open)"last candle up" else "last candle down"
 }
}