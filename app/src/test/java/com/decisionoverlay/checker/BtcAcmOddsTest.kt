package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcAcmOddsTest {
    private fun trained(): BtcAcmOdds.Model {
        val model=BtcAcmOdds.Model()
        repeat(100){ i->
            model.addMarket(listOf(BtcAcmOdds.Sample(
                4,1,1.5,i<92)),false)
        }
        repeat(50){ i->
            model.addMarket(listOf(BtcAcmOdds.Sample(
                4,1,1.5,i<46)),true)
        }
        return model
    }
    @Test fun empiricalOddsSeparateFromKalshiAskAndIncludeFees() {
        val model=trained()
        val d=BtcAcmOdds.decide(model,100.06,100.0,0.02,
            240.0,0.60,0.60)
        assertEquals(1,d.side)
        assertEquals("YES",d.label)
        assertTrue(d.qualified)
        assertTrue(d.selected!!.conservative>d.breakEven+0.02)
        assertTrue(d.breakEven>0.60)
        assertTrue(d.reason.startsWith("BUY YES"))
    }
    @Test fun sameBtcLevelDoesNotBuyIfKalshiMoreExpensive() {
        val model=trained()
        val d=BtcAcmOdds.decide(model,100.06,100.0,0.02,
            240.0,0.96,0.60)
        assertFalse(d.qualified)
        assertNull(d.required)
    }
    @Test fun noQuoteOrNoHistoricalModelMeansNoBuy() {
        val model=trained()
        assertFalse(BtcAcmOdds.decide(model,100.06,100.0,0.02,
            240.0,null,0.60).qualified)
        assertFalse(BtcAcmOdds.decide(null,100.06,100.0,0.02,
            240.0,0.60,0.60).qualified)
    }
    @Test fun noBuyOutsideSecondHalfRegardlessOfGoodOdds() {
        val model=trained()
        assertFalse(BtcAcmOdds.decide(model,100.06,100.0,0.02,
            540.0,0.60,0.60).qualified)
        assertFalse(BtcAcmOdds.decide(model,100.06,100.0,0.02,
            59.0,0.60,0.60).qualified)
    }
    @Test fun buyBoundaryConvergesWithRealSecondsAndRollingAcm() {
        val model=trained()
        val e=model.minimumRatioForAsk(4,1,0.60)
        assertNotNull(e)
        val r=e!!.ratio
        val t=100.0
        val acm=0.02
        val at4=t*(1.0+r*acm*4.0/100.0)
        val at3=t*(1.0+r*acm*3.0/100.0)
        assertTrue(at4>=at3)
        assertEquals(0.0,BtcAcmOdds.normalizedGap(100.0,100.0,acm,240.0),1e-9)
    }
    @Test fun serializedHistoricalCountsCanBeRestored() {
        val before=trained()
        val restored=BtcAcmOdds.Model.decode(before.encode())!!
        assertEquals(100,restored.trainMarkets)
        assertEquals(50,restored.testMarkets)
        assertEquals(before.atDistance(4,1,1.4)!!.holdout.wins,
            restored.atDistance(4,1,1.4)!!.holdout.wins)
    }
}
