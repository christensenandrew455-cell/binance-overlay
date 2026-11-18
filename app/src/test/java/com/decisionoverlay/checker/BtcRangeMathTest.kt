package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcRangeMathTest {
    @Test fun mapsTargetAndCeilingToFiftyAndNinety() {
        assertEquals(50.0, BtcRangeMath.rulePercentage(0.0, 0.1, 10)!!, 1e-9)
        assertEquals(70.0, BtcRangeMath.rulePercentage(0.5, 0.1, 10)!!, 1e-9)
        assertEquals(90.0, BtcRangeMath.rulePercentage(1.0, 0.1, 10)!!, 1e-9)
        assertEquals(90.0, BtcRangeMath.rulePercentage(10.0, 0.1, 10)!!, 1e-9)
    }
    @Test fun firstCrossingNeverCountsAgain() {
        assertEquals((51..60).toList(),
            BtcRangeMath.firstCrossed(50, 60.5).toList())
        assertTrue(BtcRangeMath.firstCrossed(60, 55.0).isEmpty())
        assertTrue(BtcRangeMath.firstCrossed(60, 60.9).isEmpty())
        assertEquals((61..65).toList(),
            BtcRangeMath.firstCrossed(60, 65.4).toList())
    }
    @Test fun syntheticAskIsIncreasingWithGapAndDecreasingTime() {
        val near = BtcRangeMath.syntheticAskPct(100.1, 100.0, 0.001, 10)!!
        val far = BtcRangeMath.syntheticAskPct(100.5, 100.0, 0.001, 10)!!
        val late = BtcRangeMath.syntheticAskPct(100.5, 100.0, 0.001, 2)!!
        assertTrue(far >= near)
        assertTrue(late >= far)
    }
    @Test fun feeUsesRoundedCents() {
        assertEquals(2.0, BtcRangeMath.illustrativeFeePct(65.0), 1e-9)
    }
}
