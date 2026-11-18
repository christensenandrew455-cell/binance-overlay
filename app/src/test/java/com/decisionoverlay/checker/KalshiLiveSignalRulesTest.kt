package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiLiveSignalRulesTest {
    private fun q(left: Int, midpoint: Double): KalshiPatternMath.Quote {
        return KalshiPatternMath.Quote(left, (midpoint - 1) / 100.0,
            (midpoint + 1) / 100.0)
    }

    @Test fun pathNeedsChronologicalOrderAndDistinctMinutes() {
        val quotes = listOf(q(13, 40.0), q(12, 55.0), q(8, 62.0))
        assertEquals(8, KalshiLiveSignalRules.firstCompletion(listOf(40, 60), quotes)?.left)
        assertNull(KalshiLiveSignalRules.firstCompletion(listOf(60, 40), quotes))
        assertNull(KalshiLiveSignalRules.firstCompletion(listOf(40, 60, 40), quotes))
    }

    @Test fun noSignalIfPathAlreadyCompletedBeforeWindow() {
        val quotes = listOf(q(13, 40.0), q(12, 61.0), q(7, 62.0))
        assertTrue(KalshiLiveSignalRules.matchingProxySignals(quotes).isEmpty())
    }

    @Test fun onlyAlertAtCompletedMinuteInSixToEightMinuteWindow() {
        assertTrue(KalshiLiveSignalRules.matchingProxySignals(
            listOf(q(11, 40.0), q(9, 61.0))).isEmpty())
        assertTrue(KalshiLiveSignalRules.matchingProxySignals(
            listOf(q(11, 40.0), q(5, 61.0))).isEmpty())
    }

    @Test fun emitsDownWatchFromSignedCrossingInsteadOfNo() {
        val quotes = listOf(q(11, 40.0), q(8, 61.0))
        val signals = KalshiLiveSignalRules.matchingProxySignals(quotes)
        val match = signals.firstOrNull { it.rule.path == listOf(40, 60) }
        assertNotNull(match)
        assertEquals("DOWN", match!!.rule.side)
        assertEquals("-60% > +60%", match.rule.name)
        assertEquals(61.0, match.signedChartLevel, 1e-5)
    }

    @Test fun emitsUpWatchAfterDownwardChartMove() {
        val quotes = listOf(q(12, 51.0), q(9, 29.0), q(7, 19.0))
        val signals = KalshiLiveSignalRules.matchingProxySignals(quotes)
        val match = signals.firstOrNull { it.rule.path == listOf(50, 20) }
        assertNotNull(match)
        assertEquals("UP", match!!.rule.side)
        assertEquals("TARGET > -80%", match.rule.name)
        assertEquals(-81.0, match.signedChartLevel, 1e-5)
        assertTrue(signals.all { it.left == 7 })
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
