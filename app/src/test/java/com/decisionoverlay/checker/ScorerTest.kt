package com.decisionoverlay.checker
import org.junit.Assert.assertEquals
import org.junit.Test
class ScorerTest {
 @Test fun bullishSetupRequiresBelowTargetAndAlignedMomentum(){
  val result=Scorer.evaluateTarget(trend(1),structure(1),trend(1),feeds(),105.0,100.0,3)
  assertEquals(10,result.score);assertEquals(Verdict.UP,result.candidate)
 }
 @Test fun bearishSetupRequiresAboveTargetAndAlignedMomentum(){
  val result=Scorer.evaluateTarget(trend(-1),structure(-1),trend(-1),feeds(),95.0,100.0,3)
  assertEquals(-10,result.score);assertEquals(Verdict.DOWN,result.candidate)
 }
 @Test fun nearTargetAlwaysStaysDnt(){
  val result=Scorer.evaluateTarget(trend(1),structure(1),trend(1),feeds(),100.0,100.01,3)
  assertEquals(Verdict.DNT,result.candidate)
 }
 @Test fun missingFeedForcesDnt(){
  val bad=feeds().toMutableList();bad[1]=FeedStatus(ConnectionState.NOT_WORKING,"offline",null,"HTTP error")
  val result=Scorer.evaluateTarget(trend(1),structure(1),trend(1),bad,105.0,100.0,3)
  assertEquals(Verdict.DNT,result.candidate);assertEquals(false,result.problems.isEmpty())
 }
 private fun feeds()=listOf("1m","5m","15m").map{FeedStatus(ConnectionState.CONNECTED,"test",1L,null)}
 private fun trend(dir:Int):List<Candle>{var p=100.0;return (0 until 45).map{i->val n=p+dir*.2;val x=Candle(i*60000L,p,maxOf(p,n)+.05,minOf(p,n)-.05,n,10.0,(i+1)*60000L-1,true);p=n;x}}
 private fun structure(dir:Int):List<Candle>{
  val out=mutableListOf<Candle>();var p=100.0
  for(i in 0 until 42){val step=if(i<39)0.02 else 0.5;val n=p+dir*step;out+=Candle(i*300000L,p,maxOf(p,n)+.1,minOf(p,n)-.1,n,10.0,(i+1)*300000L-1,true);p=n}
  return out
 }
}