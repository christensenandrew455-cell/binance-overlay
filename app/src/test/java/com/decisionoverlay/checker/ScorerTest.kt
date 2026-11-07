package com.decisionoverlay.checker
import org.junit.Assert.assertEquals
import org.junit.Test
class ScorerTest {
 @Test fun primaryFiveMinuteTriggerAloneCanSignalUp(){val f=Scorer.score5m(recent(1),connected());val d=Scorer.decide(f,Scorer.score15m(flat(),connected(),1),Scorer.score1h(flat(),connected(),1));assertEquals(6,f.score);assertEquals(6,d.totalScore);assertEquals(Verdict.UP,d.verdict)}
 @Test fun fifteenMinuteConfirmsAndHourAddsOne(){val f=Scorer.score5m(recent(1),connected());val dir=f.score.compareTo(0);val m=Scorer.score15m(structure(1),connected(),dir);val h=Scorer.score1h(trend(1),connected(),dir);val d=Scorer.decide(f,m,h);assertEquals(3,m.score);assertEquals(1,h.score);assertEquals(10,d.totalScore);assertEquals(Verdict.UP,d.verdict)}
 @Test fun bearishFiveMinuteDrivesDown(){val f=Scorer.score5m(recent(-1),connected());val dir=f.score.compareTo(0);val m=Scorer.score15m(structure(-1),connected(),dir);val h=Scorer.score1h(trend(1),connected(),dir);val d=Scorer.decide(f,m,h);assertEquals(-6,f.score);assertEquals(-3,m.score);assertEquals(-1,h.score);assertEquals(-10,d.totalScore);assertEquals(Verdict.DOWN,d.verdict)}
 @Test fun sliderThresholdRemainsAdjustable(){val f=Scorer.score5m(recent(1),connected());val m=Scorer.score15m(flat(),connected(),1);val h=Scorer.score1h(flat(),connected(),1);assertEquals(Verdict.UP,Scorer.decide(f,m,h,6).verdict);assertEquals(Verdict.UP,Scorer.decide(f,m,h,3).verdict)}
 @Test fun oldTrendWithoutFreshCrossoverDoesNotTrigger(){assertEquals(0,Scorer.score5m(trend(1),connected()).score)}
 @Test fun missingFeedForcesDnt(){val bad=FeedStatus(ConnectionState.NOT_WORKING,"Not Working",null,"HTTP error");val f=Scorer.score5m(recent(1),bad);assertEquals(Verdict.DNT,Scorer.decide(f,Scorer.score15m(flat(),connected(),1),Scorer.score1h(flat(),connected(),1)).verdict)}
 private fun connected()=FeedStatus(ConnectionState.CONNECTED,"test",1L,null)
 private fun c(i:Int,o:Double,cl:Double,hi:Double=maxOf(o,cl),lo:Double=minOf(o,cl))=Candle(i*300000L,o,hi,lo,cl,10.0,(i+1)*300000L-1,true)
 private fun flat()=(0 until 90).map{c(it,100.0,100.0)}
 private fun trend(dir:Int)=(0 until 90).map{val p=100.0+dir*it*.5;c(it,p,p+dir*.5)}
 private fun structure(dir:Int):List<Candle>{
  val baseline=if(dir>0)100.0 else 104.0
  val prefix=(0 until 39).map{idx->c(idx,baseline,baseline)}
  val tail=if(dir>0)listOf(100.0,100.2,100.4,101.0,101.2,101.4)else listOf(104.0,103.8,103.6,103.0,102.8,102.6)
  return prefix+tail.mapIndexed{idx,p->val n=39+idx;val o=if(dir>0)p-.1 else p+.1;val close=if(dir>0)p+.1 else p-.1;c(n,o,close,maxOf(o,close)+.1,minOf(o,close)-.1)}
 }
 private fun recent(dir:Int):List<Candle>{var p=100.0;val out=mutableListOf<Candle>();for(i in 0 until 60){val n=p-dir*.3;out+=c(i,p,n);p=n};for(i in 60 until 85){val n=p-dir*.6;out+=c(i,p,n);p=n};for(i in 85 until 90){val n=p+dir*(1.0+(i-85)*1.0);out+=c(i,p,n);p=n};return out}
}