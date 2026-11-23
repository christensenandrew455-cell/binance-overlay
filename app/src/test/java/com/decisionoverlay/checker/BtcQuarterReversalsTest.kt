package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcQuarterReversalsTest {
    @Test fun midpointAndWickGuardWithSeparateIndicatorCounts() {
        val candle = BtcAcmMath.Candle(0L, 101.0, 106.0, 94.0, 105.0)
        assertEquals(100.0, candle.midpoint, 0.0)
        val bars = listOf(
            BinanceTwentySevenPatternReport.Observation(1, 90.0, 102.0, 105.0),
            BinanceTwentySevenPatternReport.Observation(2, 73.0, 101.0, 104.0))
        val win = BinanceTwentySevenPatternReport.Market(0L, candle.midpoint, 98.0, bars)
        val loss = win.copy(startMs = 900000L, finalBtc = 104.0)
        val crossed = win.copy(startMs = 1800000L, minutes = listOf(bars[0], bars[1].copy(low = 99.0)))
        val features = mapOf(0L to mapOf(2 to BtcEarlyIndicatorResearchReport.Features(1.2, -0.1, 0.0)))
        val report = BtcQuarterReversals.report(listOf(win, loss, crossed), features)
        assertTrue(report.contains("1m drop,90,70,DOWN,13,none,2,66.67,1,50.00,30,false"))
        assertTrue(report.contains("1m drop,90,70,DOWN,13,momentum+volume,1,33.33,1,100.00,30,true"))
        assertTrue(report.contains("level,,-60,UP,1,none,0,0.00,0,NA,40,NA"))
    }
}
