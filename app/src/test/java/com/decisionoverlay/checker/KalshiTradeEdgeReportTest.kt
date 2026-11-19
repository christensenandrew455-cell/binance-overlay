package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiTradeEdgeReportTest {
    private fun quote(left: Int, yesPercent: Double) =
        KalshiPatternMath.Quote(left, (yesPercent - 1) / 100.0,
            (yesPercent + 1) / 100.0)

    @Test fun twoAndThreePointPathsAreSeparateAndOrdered() {
        val series = listOf(quote(11, 70.0), quote(9, 50.0), quote(6, 60.0))
        assertEquals(6, KalshiTradeEdgeReport.firstTradeCompletion(
            series, listOf(70, 50, 60))?.left)
        assertEquals(9, KalshiTradeEdgeReport.firstTradeCompletion(
            series, listOf(70, 50))?.left)
        assertEquals(6, KalshiTradeEdgeReport.firstTradeCompletion(
            series, listOf(70, 60))?.left)
        assertNull(KalshiTradeEdgeReport.firstTradeCompletion(
            series, listOf(60, 50, 70)))
    }

    @Test fun skippedPricesAndSameMinuteAreNotInventedTouches() {
        assertNull(KalshiTradeEdgeReport.firstTradeCompletion(
            listOf(quote(10, 70.0), quote(6, 40.0)), listOf(70, 60)))
        assertNull(KalshiTradeEdgeReport.firstTradeCompletion(
            listOf(quote(10, 70.0), quote(10, 60.0)), listOf(70, 60)))
    }

    @Test fun firstThreeMinutesAreResearchOnly() {
        assertNull(KalshiTradeEdgeReport.firstTradeCompletion(
            listOf(quote(15, 70.0), quote(14, 60.0)), listOf(70, 60)))
        assertEquals(10, KalshiTradeEdgeReport.firstTradeCompletion(
            listOf(quote(15, 70.0), quote(14, 60.0),
                quote(12, 70.0), quote(10, 60.0)), listOf(70, 60))?.left)
    }

    @Test fun compareWinRateToActualBoughtSideAskNotOtherSideMidpoint() {
        val s = KalshiTradeEdgeReport.Stats()
        val yesQuote = quote(7, 60.0) // YES ask 61c, NO ask 41c
        for (i in 0 until 100) {
            s.add(KalshiTradeEdgeReport.Event(i, yesQuote, i >= 60), -1)
        }
        assertEquals(60.0, s.winPct(), 0.0001)
        assertEquals(41.0, s.askCents(), 0.0001)
        assertEquals(19.0, s.edgePoints(), 0.0001)
        assertTrue(s.edgePoints() < 20.0)
        // An exactly 40c executable NO ask would give a full 20pp edge.
        val exact = KalshiTradeEdgeReport.Stats()
        for (i in 0 until 100) {
            exact.add(KalshiTradeEdgeReport.Event(i,
                KalshiPatternMath.Quote(7, 0.60, 0.60), i >= 60), -1)
        }
        assertEquals(20.0, exact.edgePoints(), 0.0001)
    }

    @Test fun reportIncludesSeparatelyQualifiedTwoPointMirrors() {
        val markets = (0 until 1000).map { i ->
            val first = i < 180
            val mirror = i in 200 until 380
            val candles = when {
                first -> listOf(quote(10, 70.0), quote(7, 60.0))
                mirror -> listOf(quote(10, 30.0), quote(7, 40.0))
                else -> listOf(quote(10, 55.0), quote(7, 55.0))
            }
            val yesWon = when {
                first -> i % 10 >= 7 // 70% DOWN winners
                mirror -> i % 10 < 7 // 70% UP winners
                else -> i % 2 == 0
            }
            KalshiPatternMath.Market("M-$i", 900L * i, yesWon, candles)
        }
        val report = KalshiTradeEdgeReport.report(markets)
        assertTrue(report.contains("YES 70% -> 60% | BUY DOWN (NO)"))
        assertTrue(report.contains("YES 30% -> 40% | BUY UP (YES)"))
        assertTrue(report.contains("QUALIFIED SEPARATELY"))
        assertTrue(report.contains("Combined distinct-market hit: 360/1000 (36.0%)"))
        assertTrue(report.contains("Discovery:"))
        assertTrue(report.contains("Newest:"))
    }

    @Test fun threePointPatternsShowSameWindowTwoPointComparison() {
        val markets = (0 until 1000).map { i ->
            val hit = i < 150
            val candles = if (hit) listOf(
                quote(11, 70.0), quote(8, 50.0), quote(6, 60.0))
            else listOf(quote(11, 55.0), quote(6, 55.0))
            KalshiPatternMath.Market("M-$i", 900L * i,
                if (hit) i % 10 >= 7 else i % 2 == 0, candles)
        }
        val report = KalshiTradeEdgeReport.report(markets)
        assertTrue(report.contains("3-POINT | YES 70% -> 50% -> 60%"))
        assertTrue(report.contains("SAME-WINDOW 2-point YES 70% -> 50%:"))
    }
}
