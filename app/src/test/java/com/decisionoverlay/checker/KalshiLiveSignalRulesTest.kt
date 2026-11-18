package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiLiveSignalRulesTest {
    private fun q(left: Int, signedLevel: Double) =
        BtcChartProxyMath.Sample(left, signedLevel)

    @Test fun pathNeedsChronologicalOrderAndDistinctMinutes() {
        val samples = listOf(q(13, -65.0), q(12, 0.0), q(8, 62.0))
        assertEquals(8, KalshiLiveSignalRules.firstCompletion(
            listOf(40, 60), samples)?.left)
        assertNull(KalshiLiveSignalRules.firstCompletion(
            listOf(60, 40), samples))
        assertNull(KalshiLiveSignalRules.firstCompletion(
            listOf(40, 60, 40), samples))
    }

    @Test fun noSignalIfPathAlreadyCompletedBeforeWindow() {
        val samples = listOf(q(13, -61.0), q(12, 61.0), q(7, 62.0))
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(samples).isEmpty())
    }

    @Test fun onlyAlertAtCompletedMinuteInSixToEightMinuteWindow() {
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(11, -61.0), q(9, 61.0))).isEmpty())
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(
            listOf(q(11, -61.0), q(5, 61.0))).isEmpty())
    }

    @Test fun emitsDownWatchWhenCurrentChartAboveTarget() {
        val samples = listOf(q(11, -61.0), q(8, 61.0))
        val signals = KalshiLiveSignalRules.matchingSignedSignals(samples)
        val match = signals.firstOrNull { it.rule.path == listOf(40, 60) }
        assertNotNull(match)
        assertEquals("DOWN", match!!.rule.side)
        assertEquals("-60% > +60%", match.rule.name)
        assertEquals(61.0, match.signedChartLevel, 1e-5)
        assertTrue(signals.all { it.signedChartLevel > 0.0 &&
            it.rule.side == "DOWN" })
    }

    @Test fun emitsUpWatchWhenCurrentChartBelowTarget() {
        val samples = listOf(q(12, 51.0), q(9, -71.0), q(7, -81.0))
        val signals = KalshiLiveSignalRules.matchingSignedSignals(samples)
        val match = signals.firstOrNull { it.rule.path == listOf(50, 20) }
        assertNotNull(match)
        assertEquals("UP", match!!.rule.side)
        assertEquals("TARGET > -80%", match.rule.name)
        assertEquals(-81.0, match.signedChartLevel, 1e-5)
        assertTrue(signals.all { it.signedChartLevel < 0.0 &&
            it.rule.side == "UP" })
    }

    @Test fun cannotMatchOldQuotePercentagesAsUnsignedChartData() {
        val samples = listOf(q(11, 40.0), q(8, 60.0))
        assertTrue(KalshiLiveSignalRules.matchingSignedSignals(samples).isEmpty())
    }

    @Test fun oldTwentyQuoteIsNegativeEightyOnCustomChartScale() {
        assertEquals(-80.0, BtcChartProxyMath.signedFromProxy(20.0), 0.00001)
        assertEquals(-60.0, BtcChartProxyMath.signedFromProxy(40.0), 0.00001)
        assertEquals(0.0, BtcChartProxyMath.signedFromProxy(50.0), 0.00001)
        assertEquals(70.0, BtcChartProxyMath.signedFromProxy(70.0), 0.00001)
        assertEquals("-80%", BtcChartProxyMath.signedPathLevel(20))
        assertEquals("+70%", BtcChartProxyMath.signedPathLevel(70))
    }
}
