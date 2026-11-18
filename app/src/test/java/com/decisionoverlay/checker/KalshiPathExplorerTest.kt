package com.decisionoverlay.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiPathExplorerTest {
    @Test fun reportsActualOutcomeAfterDropAndRecoveryByMinute() {
        val market = KalshiPatternMath.Market(
            "KXBTC15M-TEST", 123456789L, true, listOf(
                KalshiPatternMath.Quote(14, 0.81, 0.83),
                KalshiPatternMath.Quote(12, 0.59, 0.61),
                KalshiPatternMath.Quote(9, 0.70, 0.72)))
        val output = KalshiPathExplorer.report(listOf(market))
        assertTrue(output.contains("YES,80,60,12,1,1,100.0%,61.00,2.00,37.00"))
        assertTrue(output.contains("YES,80,60,70,9,1,1,100.0%,72.00,2.00,26.00"))
        assertTrue(output.contains("YES,80,60,14,12,1,1,100.0%"))
        assertTrue(output.contains("YES,80,60,70,14,12,9,1,1,100.0%"))
        assertTrue(output.contains("YES,80,60,0,0,0,n/a"))
    }

    @Test fun doesNotCountRecoveryBeforeTheDrop() {
        val market = KalshiPatternMath.Market(
            "KXBTC15M-NORECOVERY", 123456789L, false, listOf(
                KalshiPatternMath.Quote(14, 0.81, 0.83),
                KalshiPatternMath.Quote(13, 0.70, 0.72),
                KalshiPatternMath.Quote(12, 0.59, 0.61)))
        val output = KalshiPathExplorer.report(listOf(market))
        assertTrue(output.contains("YES,80,60,1,0,0.0%"))
        assertTrue(output.contains("YES,80,60,70,0,0,n/a"))
    }
}
