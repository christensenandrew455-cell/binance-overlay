package com.decisionoverlay.checker
import org.junit.Assert.assertEquals
import org.junit.Test
class ScorerTest {
 @Test fun fiveMinutePrimaryTriggerCanSignalUp(){val one=Scorer.score1m(flat(),connected());val five=Scorer.score5m(recent(1),connected());val fifteen=Scorer.score15m(flat(),connected(),1);val d=Scorer.decide(one,five,fifteen);assertEquals(6,five.score);assertEquals(6,d.totalScore);assertEquals(Verdict.UP,d.verdict)}
 @Test fun oneMinuteEarlyMomentumAddsToFiveMinuteSignal(){val one=Scorer.score1m(recent(1),connected());val five=Scorer.score5m(recent(1),connected());val fifteen=Scorer.score15m(structure(1),connected(),1);assertEquals(2,one.score);assertEquals(2,fifteen.score);assertEquals(10,Scorer.decide(one,five,fifteen).totalScore)}
 @Test fun bearishFiveMinuteDrivesDown(){val one=Scorer.score1m(recent(-1),connected());val five=Scorer.score5m(recent(-1),connected());val fifteen=Scorer.score15m(structure(-1),connected(),-1);val d=Scorer.decide(one,five,fifteen);assertEquals(-6,five.score);assertEquals(-10,d.totalScore);assertEquals(Verdict.DOWN,d.verdict)}
 @Test fun thresholdRemainsAdjustable(){val one=Scorer.score1m(flat(),connected());val five=Scorer.score5m(recent(1),connected());val fifteen=Scorer.score15m(flat(),connected(),1);assertEquals(Verdict.UP,Scorer.decide(one,five,fifteen,6).verdict);assertEquals(Verdict.UP,Scorer.decide(one,five,fifteen,3).verdict)}
 @Test fun missingFeedForcesDnt(){val bad=FeedStatus(ConnectionState.NOT_WORKING,"Not Working",null,"HTTP error");val d=Scorer.decide(Scorer.score1m(flat(),connected()),Scorer.score5m(recent(1),bad),Scorer.score15m(flat(),connected(),1));assertEquals(Verdict.DNT,d.verdict)}
 private fun connected()=FeedStatus(ConnectionState.CONNECTED,"test",1L,null)
 private fun c(i:Int,o:Double,cl:Double,hi:Double=maxOf(o,cl),lo:Double=minOf(o,cl))=Candle(i*60000L,o,hi,lo,10.0,(i+1)*60000L-1,true)
 private fun flat()=(0 until 90).map{c(it,100.0,100.0)}
 private fun recent(dir:Int):List<Candle>{var p=100.0;val out=mutableListOf<Candle>();for(i in 0 until 60){val n=p-dir*.3;out+=c(i,p,n);p=n};for(i in 60 until 85){val n=p-dir*.6;out+=c(i,p,n);p=n};for(i in 85 until 90){val n=p+dir*(1.0+(i-85)*1.0);out+=c(i,p,n);p=n};return out}
 private fun structure(dir:Int):List<Candle>{val base=if(dir>0)100.0 else 104.0;val prefix=(0 until 39).map{c(it,base,base)};val tail=if(dir>0)listOf(100.0,100.2,100.4,101.0,101.2,101.4)else listOf(104.0,103.8,103.6,103.0,102.8,102.6);return prefix+tail.mapIndexed{idx,p->val n=39+idx;val o=if(dir>0)p-.1 else p+.1;val cl=if(dir>0)p+.1 else p-.1;c(n,o,cl,maxOf(o,cl)+.1,minOf(o,cl)-.1)}}
}