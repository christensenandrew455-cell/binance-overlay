package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcAnytimeExtremeResearchReportTest {
    private val subject = BtcAnytimeExtremeResearchReport
    private fun market(id: Long, up: Boolean, vararg points: Pair<Int, Double>) =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L, 100.0, if (up) 101.0 else 99.0,
            points.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) }
        )

    @Test fun priorMayBeAnyEarlierMinuteNotJustAdjacent() {
        val a = market(1, false,
            1 to -84.0, 2 to -64.0, 3 to 58.0, 4 to 74.0, 5 to 94.0,
            6 to -56.0)
        val q = subject.queries.first { it.extreme == 90 &&
            it.prior == BtcAnytimeExtremeResearchReport.Prior.OPPOSITE_80 }
        val rows = subject.hits(listOf(a), emptyMap(), q,
            BtcEarlyIndicatorResearchReport.Gate.NONE)
        assertEquals(1, rows.size)
        assertEquals(5, rows[0].minute)
        assertEquals(10, rows[0].left)
        assertTrue(rows[0].wonOpposite)
        assertTrue(rows[0].returnedOppositeBeforeClose)
    }

    @Test fun laterOppositeTouchDoesNotCountAsPrior() {
        val a = market(1, false, 2 to 94.0, 5 to -84.0)
        val b = market(2, false, 2 to -84.0, 5 to 94.0)
        val q = subject.queries.first { it.extreme == 90 &&
            it.prior == BtcAnytimeExtremeResearchReport.Prior.OPPOSITE_80 }
        val rows = subject.hits(listOf(a,b), emptyMap(), q,
            BtcEarlyIndicatorResearchReport.Gate.NONE)
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].marketIndex)
    }

    @Test fun strongNegativeExtremeScoresUpSettlementAndReturn() {
        val a = market(1, true, 1 to 85.0, 6 to -95.0, 12 to 52.0)
        val q = subject.queries.first { it.extreme == -90 &&
            it.prior == BtcAnytimeExtremeResearchReport.Prior.OPPOSITE_80 }
        val row = subject.hits(listOf(a), emptyMap(), q,
            BtcEarlyIndicatorResearchReport.Gate.NONE).single()
        assertEquals(6, row.minute)
        assertTrue(row.wonOpposite)
        assertTrue(row.returnedOppositeBeforeClose)
    }

    @Test fun reportHasExactMinuteAndNonadjacentHistoryAndNoPriceClaims() {
        val markets = listOf(
            market(1, false, 1 to -85.0, 5 to 95.0),
            market(2, true, 1 to 95.0, 5 to -85.0)
        )
        val report = subject.report(markets, emptyMap())
        assertTrue(report.contains("ANYTIME 90 TOUCH"))
        assertTrue(report.contains("ANY EARLIER candle"))
        assertTrue(report.contains("90-FIRST-TOUCH FREQUENCY BY EXACT MINUTE"))
        assertTrue(report.contains("NOT a 90-cent Kalshi contract"))
        assertTrue(report.contains("NO MAIN GRAPH"))
    }
}
