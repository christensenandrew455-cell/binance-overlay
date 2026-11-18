package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiPatternMathTest {
    private fun quote(left: Int, yesCents: Double) =
        KalshiPatternMath.Quote(left, (yesCents - 2.0) / 100.0,
            (yesCents + 2.0) / 100.0)

    @Test fun hitsThenFallsTracksActualNoSettlement() {
        val market = KalshiPatternMath.Market("A", 900L, false, listOf(
            quote(7, 50.0), quote(6, 71.0), quote(5, 76.0),
            quote(4, 59.0), quote(3, 49.0), quote(2, 40.0), quote(1, 35.0)))
        val events = KalshiPatternMath.analyze(market)
        val yes70 = events.reaches.single { it.side == 1 && it.level == 70 }
        assertEquals(6, yes70.left)
        assertEquals(true, yes70.fellBelow)
        assertFalse(yes70.won)
        val retrace = events.pullbacks.single {
            it.side == 1 && it.level == 70 && it.retrace == 50
        }
        assertEquals(3, retrace.left)
        assertTrue(retrace.net < 0)
        assertEquals(1, events.reaches.count { it.side == 1 && it.level == 70 })
    }

    @Test fun completeStayIsDistinctFromUnknownWithMissingMinutes() {
        val complete = KalshiPatternMath.Market("A", 900, true,
            (1..6).map { quote(it, 70.0) })
        val missing = KalshiPatternMath.Market("B", 1800, true, listOf(
            quote(6, 72.0), quote(4, 75.0), quote(2, 73.0), quote(1, 80.0)))
        val a = KalshiPatternMath.analyze(complete).reaches
            .single { it.side == 1 && it.level == 70 }
        val b = KalshiPatternMath.analyze(missing).reaches
            .single { it.side == 1 && it.level == 70 }
        assertEquals(false, a.fellBelow)
        assertNull(b.fellBelow)
        assertTrue(a.won)
    }

    @Test fun noSideIsTrackedWithComplementOfYesQuote() {
        val market = KalshiPatternMath.Market("NO", 900, false, listOf(
            quote(6, 48.0), quote(5, 28.0),
            quote(4, 22.0), quote(3, 32.0), quote(2, 41.0), quote(1, 53.0)))
        val e = KalshiPatternMath.analyze(market)
        val no70 = e.reaches.single { it.side == -1 && it.level == 70 }
        assertTrue(no70.won)
        assertEquals(true, no70.fellBelow)
        assertEquals(1, e.pullbacks.count {
            it.side == -1 && it.level == 70 && it.retrace == 60
        })
    }

    @Test fun fakeOrUnusableQuotesNeverBecomeProbabilities() {
        val market = KalshiPatternMath.Market("INVALID", 900, true, listOf(
            KalshiPatternMath.Quote(6, 0.0, 1.0),
            KalshiPatternMath.Quote(5, Double.NaN, 0.8)))
        assertTrue(KalshiPatternMath.analyze(market).reaches.isEmpty())
    }

    @Test fun reportIsCompactAndKeepsBothSidesAndSampleSizes() {
        val markets = listOf(KalshiPatternMath.Market("A", 900L, true,
            listOf(quote(2, 60.0), quote(1, 70.0))))
        val result = KalshiCompactReport.report(markets)
        assertTrue(result.contains("=== COMPACT BTC 15-MINUTE KALSHI BACKTEST ==="))
        assertTrue(result.contains("YES 60: wins 1/1 (100.0%)"))
        assertTrue(result.contains("NO 90: 0 observed"))
        assertTrue(result.contains("No recovery path passed"))
        assertTrue(result.length < 15000)
    }

    @Test fun reportPreservesOrderedRecoveryStatisticsAndTimeBuckets() {
        val market = KalshiPatternMath.Market(
            "R", 900L, true, listOf(
                KalshiPatternMath.Quote(14, 0.81, 0.83),
                KalshiPatternMath.Quote(12, 0.59, 0.61),
                KalshiPatternMath.Quote(9, 0.70, 0.72)))
        val result = KalshiCompactReport.report(listOf(market))
        assertTrue(result.contains("YES 80->60->70: 1/1 (100.0%)"))
        assertTrue(result.contains("avg minutes left high/drop/recovery: 14.0/12.0/9.0"))
        assertTrue(result.contains("10-6m n=1 win=100.0%"))
    }
}
