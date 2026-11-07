package com.decisionoverlay.checker
object Scorer {
 const val MIN_CANDLES=40
 const val DEFAULT_DNT_THRESHOLD=3
 const val MIN_DNT_THRESHOLD=2
 const val MAX_DNT_THRESHOLD=6
 private const val CROSS_HORIZON=3
 fun score5m(c:List<Candle>,f:FeedStatus)=score(c,f,"5-minute","5m",::fiveParts)
 fun score15m(c:List<Candle>,f:FeedStatus,direction:Int=0)=score(c,f,"15-minute","15m"){d,p->fifteenParts(d,direction)}
 fun score1h(c:List<Candle>,f:FeedStatus,direction:Int=0)=score(c,f,"1-hour","1h"){d,p->hourParts(d,p,direction)}
 fun timeframeBias(s:TimeframeScore):Verdict{if(s.connection!=ConnectionState.CONNECTED||s.candleCount<MIN_CANDLES)return Verdict.DNT;return when{s.score>0->Verdict.UP;s.score<0->Verdict.DOWN;else->Verdict.DNT}}
 fun decide(f:TimeframeScore,m:TimeframeScore,h:TimeframeScore,dntThreshold:Int=DEFAULT_DNT_THRESHOLD):Decision{
  val t=dntThreshold.coerceIn(MIN_DNT_THRESHOLD,MAX_DNT_THRESHOLD);val problems=mutableListOf<String>();val all=listOf(f,m,h)
  all.filter{it.candleCount<MIN_CANDLES}.forEach{problems.add(it.label+": need at least "+MIN_CANDLES+" completed candles; received "+it.candleCount+".")}
  all.filter{it.connection!=ConnectionState.CONNECTED}.forEach{problems.add(it.label+": Binance feed is not connected ("+it.connectionDetail+").");if(!it.lastError.isNullOrBlank())problems.add(it.label+": "+it.lastError)}
  val total=(f.score+m.score+h.score).coerceIn(-10,10)
  val v=when{problems.isNotEmpty()->Verdict.DNT;total>=t->Verdict.UP;total<=-t->Verdict.DOWN;else->Verdict.DNT}
  val reason=when(v){Verdict.UP->"UP: weighted score "+signed(total)+" reaches +"+t+".";Verdict.DOWN->"DOWN: weighted score "+signed(total)+" reaches -"+t+".";Verdict.DNT->if(problems.isNotEmpty())"DNT: market data is incomplete or unavailable." else "DNT: score "+signed(total)+" is inside ±"+t+"."}
  return Decision(v,reason,f,m,h,problems,total,t)
 }
 private fun score(c:List<Candle>,feed:FeedStatus,label:String,interval:String,fn:(List<Candle>,Double)->List<ComponentScore>):TimeframeScore{
  val d=c.filter{it.closed}.sortedBy{it.openTime};val closes=d.map{it.close};val price=closes.lastOrNull()
  val fast=when(interval){"1h"->if(price==null)null else Indicators.ema(closes,20);"5m"->if(price==null)null else Indicators.ema(closes,8);else->null}
  val slow=if(interval=="5m"&&price!=null)Indicators.ema(closes,21)else null;val rsi=if(price==null)null else Indicators.rsi(closes);val macd=if(price==null)null else Indicators.macd(closes)
  val parts=if(d.size<MIN_CANDLES||price==null)listOf(ComponentScore("Data",0,"Need "+MIN_CANDLES+" completed candles, have "+d.size+"."))else fn(d,price)
  return TimeframeScore(label,interval,parts.sumOf{it.points},parts,price,fast,slow,rsi,macd?.line,macd?.signal,macd?.histogram,if(d.isNotEmpty()&&d.last().close>=d.last().open)"last candle up" else "last candle down",Indicators.volumeRatio(d),feed.state,feed.detail,feed.updatedAt,feed.lastError,d.size)
 }
 private fun fiveParts(d:List<Candle>,price:Double):List<ComponentScore>{
  val ema=Indicators.ema(d.map{it.close},8);val macd=Indicators.macd(d.map{it.close});val recent=macd?.barsSinceCross?.let{it<=CROSS_HORIZON}==true
  val pts=when{ema==null||macd==null||!recent->0;price>ema&&macd.crossDirection==1->6;price<ema&&macd.crossDirection==-1->-6;else->0}
  val age=macd?.barsSinceCross?.let{it.toString()+" candles"}?:"none"
  val detail=when(pts){6->"Price above EMA8 and bullish MACD crossover within last 3 completed 5m candles (+6).";-6->"Price below EMA8 and bearish MACD crossover within last 3 completed 5m candles (-6).";else->"No fresh aligned EMA8/MACD crossover trigger (0). Crossover age: "+age+"."}
  return listOf(ComponentScore("5M immediate trigger",pts,detail))
 }
 private fun fifteenParts(d:List<Candle>,direction:Int):List<ComponentScore>{
  val s=structure(d);val pts=when{direction>0&&s>0->3;direction<0&&s<0->-3;else->0};val desc=when(s){1->"higher highs and higher lows";-1->"lower highs and lower lows";else->"mixed/ranging structure"}
  val detail=when{pts>0->"15m "+desc+" confirms 5m UP (+3).";pts<0->"15m "+desc+" confirms 5m DOWN (-3).";else->"15m "+desc+" does not confirm 5m direction (0)."}
  return listOf(ComponentScore("15M structure confirmation",pts,detail))
 }
 private fun structure(d:List<Candle>):Int{if(d.size<6)return 0;val old=d.takeLast(6).take(3);val recent=d.takeLast(3);val hh=recent.maxOf{it.high}>old.maxOf{it.high};val hl=recent.minOf{it.low}>old.minOf{it.low};val lh=recent.maxOf{it.high}<old.maxOf{it.high};val ll=recent.minOf{it.low}<old.minOf{it.low};return when{hh&&hl->1;lh&&ll->-1;else->0}}
 private fun hourParts(d:List<Candle>,price:Double,direction:Int):List<ComponentScore>{
  val ema=Indicators.ema(d.map{it.close},20);val rsi=Indicators.rsi(d.map{it.close});val dist=if(ema==null||price==0.0)Double.POSITIVE_INFINITY else kotlin.math.abs(price-ema)/price
  val range=dist<=0.001||(rsi!=null&&rsi in 45.0..55.0);val trend=when{range||ema==null||rsi==null->0;price>ema&&rsi>55.0->1;price<ema&&rsi<45.0->-1;else->0}
  val pts=when{direction==0||trend==0->0;direction==trend->1;else->-1};val desc=when(pts){1->"1h supports 5m direction (+1).";-1->"1h actively opposes 5m direction (-1).";else->"1h is ranging/mixed or has no clear filter (0)."}
  return listOf(ComponentScore("1H weak directional filter",pts,"Price "+fmtNum(price)+" vs EMA20 "+fmtNum(ema)+"; RSI "+fmtNum(rsi)+". "+desc))
 }
}