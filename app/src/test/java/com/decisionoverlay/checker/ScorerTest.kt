package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorerTest {
 @Test fun strongBullTrendAndVolumeProducesTen(){
  val d=scoreAll(trend(1,true));assertEquals(4,d.hour.score);assertEquals(4,d.fifteen.score);assertEquals(2,d.five.score);assertEquals(10,d.totalScore);assertEquals(Verdict.UP,d.verdict)
 }
 @Test fun strongBearTrendAndVolumeProducesMinusTen(){
  val d=scoreAll(trend(-1,true));assertEquals(-4,d.hour.score);assertEquals(-4,d.fifteen.score);assertEquals(-2,d.five.score);assertEquals(-10,d.totalScore);assertEquals(Verdict.DOWN,d.verdict)
 }
 @Test fun thresholdCanBeAdjustedFromTwoToSix(){
  val up=trend(1,false);val five=Scorer.score5m(trend(-1,false),connected());val mid=Scorer.score15m(up,connected());val hour=Scorer.score1h(up,connected())
  assertEquals(4,Scorer.decide(five,mid,hour,2).totalScore);assertEquals(Verdict.UP,Scorer.decide(five,mid,hour,2).verdict);assertEquals(Verdict.DNT,Scorer.decide(five,mid,hour,6).verdict)
 }
 @Test fun flatMarketProducesZeroAndDnt(){
  val c=(0 until 90).map{i->Candle(i*60000L,100.0,100.0,100.0,100.0,10.0,(i+1)*60000L-1,true)}
  val d=scoreAll(c);assertEquals(0,d.totalScore);assertEquals(Verdict.DNT,d.verdict)
 }
 @Test fun unavailableFeedAlwaysForcesDntEvenWithBullishScore(){
  val c=trend(1,true);val bad=FeedStatus(ConnectionState.NOT_WORKING,"Not Working",null,"HTTP error")
  val d=Scorer.decide(Scorer.score5m(c,bad),Scorer.score15m(c,connected()),Scorer.score1h(c,connected()))
  assertEquals(10,d.totalScore);assertEquals(Verdict.DNT,d.verdict);assertTrue(d.problems.isNotEmpty())
 }
 private fun scoreAll(c:List<Candle>)=Scorer.decide(Scorer.score5m(c,connected()),Scorer.score15m(c,connected()),Scorer.score1h(c,connected()))
 private fun connected()=FeedStatus(ConnectionState.CONNECTED,"test",1L,null)
 private fun trend(dir:Int,highVolume:Boolean):List<Candle>{var p=100.0;return (0 until 90).map{i->val o=p;p+=dir*.5;Candle(i*60000L,o,maxOf(o,p),minOf(o,p),p,if(highVolume&&i==89)50.0 else 10.0,(i+1)*60000L-1,true)}}
}
