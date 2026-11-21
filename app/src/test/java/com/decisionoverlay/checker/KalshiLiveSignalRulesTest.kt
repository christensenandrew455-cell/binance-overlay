package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiLiveSignalRulesTest {
    private fun q(left: Int, level: Double) = BtcChartProxyMath.Sample(left, level)

    @Test fun onlyFourHistoricalPatternsMeetTwentyPercentFloor() {
        assertEquals(4, KalshiLiveSignalRules.rules.size)
        assertEquals(listOf(
            "-50 -> -60 in 2 min", "-50 -> -60 in 1 min",
            "+50 -> +60 in 1 min", "+50 -> +60 in 2 min"
        ), KalshiLiveSignalRules.rules.map { it.name })
        assertEquals(listOf("DOWN", "DOWN", "UP", "UP"),
            KalshiLiveSignalRules.rules.map { it.side })
        assertTrue(KalshiLiveSignalRules.rules.all {
            it.hitRatePercent >= KalshiLiveSignalRules.MIN_HIT_RATE_PERCENT
        })
        assertEquals(34.3, KalshiLiveSignalRules.rules[0].hitRatePercent, 0.001)
        assertEquals(79.6, KalshiLiveSignalRules.rules[0].winRatePercent, 0.1)
    }

    @Test fun exactNegativeTenPointBinsNotThresholdCrossing() {
        val down = KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(14, -55.0), q(12, -65.0)))
        assertEquals(1, down.size)
        assertEquals("DOWN", down.single().rule.side)
        assertEquals(2, down.single().rule.gapMinutes)
        assertEquals(14, down.single().startLeft)
        assertEquals(12, down.single().left)
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(14, -55.0), q(12, -75.0))).isEmpty())
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(14, -55.0), q(12, -59.0))).isEmpty())
    }

    @Test fun exactPositiveOneMinuteAndDirection() {
        val up = KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(10, 55.2), q(9, 61.1)))
        assertEquals(1, up.size)
        assertEquals("UP", up.single().rule.side)
        assertEquals(1, up.single().rule.gapMinutes)
    }

    @Test fun neverConfusePositiveWithNegativeFiftyOrIncorrectGap() {
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(12, 55.0), q(11, -65.0))).isEmpty())
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(12, -55.0), q(9, -65.0))).isEmpty())
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(12, -55.0), q(11, 65.0))).isEmpty())
    }

    @Test fun firstCompletionOnlyAndNoFutureReading() {
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(14, -55.0), q(13, -65.0), q(12, -55.0), q(11, -65.0)))
            .none { it.rule.gapMinutes == 1 })
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(14, -55.0))).isEmpty())
    }
}
