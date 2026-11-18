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
        assertTrue(KalshiLiveSignalRules.matchingSignals(quotes, .60, .62).isEmpty())
    }

    @Test fun noSignalWithoutPositivePriceAdvantageOrValidSpread() {
        val quotes = listOf(q(11, 40.0), q(8, 60.0))
        assertTrue(KalshiLiveSignalRules.matchingSignals(quotes, .50, .51).isEmpty())
        assertTrue(KalshiLiveSignalRules.matchingSignals(quotes, .28, .40).isEmpty())
    }

    @Test fun emitsLiveNoSignalAtReasonableAsk() {
        val quotes = listOf(q(11, 40.0), q(8, 61.0))
        val signals = KalshiLiveSignalRules.matchingSignals(quotes, .69, .71)
        val match = signals.firstOrNull { it.rule.name == "40>60" }
        assertNotNull(match)
        assertEquals("NO", match!!.rule.side)
        assertEquals(.31, match.liveAsk, 1e-6)
        assertTrue(match.estimatedEdge > .03)
    }

    @Test fun yesDipSignalAndSingleCompletionPerMarket() {
        val quotes = listOf(q(12, 51.0), q(9, 29.0), q(7, 19.0))
        val signals = KalshiLiveSignalRules.matchingSignals(quotes, .12, .14)
        assertTrue(signals.any { it.rule.name == "50>20" && it.rule.buyYes })
        assertTrue(signals.all { it.left == 7 })
    }
}
