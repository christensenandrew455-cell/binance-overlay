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


    @Test fun combinedDirectionsRequireLaterFlipAndOppositeSettlement() {
        // +90 -> -50 later -> DOWN; and -90 -> +50 later -> UP.
        val down = market(10, false, 1 to 92.0, 7 to -52.0)
        val up = market(11, true, 2 to -93.0, 9 to 55.0)
        // Settled DOWN but there was never a completed opposite-side candle.
        val settlementOnly = market(12, false, 3 to 94.0, 6 to 83.0)
        // Crosses the center but settles on the SAME side it first touched.
        val falseReversal = market(13, true, 4 to 94.0, 8 to -57.0)
        val markets = listOf(down, up, settlementOnly, falseReversal)

        val plus = subject.flipEvents(markets, 90, true)
        val minus = subject.flipEvents(markets, 90, false)
        assertEquals(3, plus.size)
        assertEquals(1, minus.size)
        assertEquals(2, plus.count { it.settledOpposite })
        assertEquals(1, plus.count { it.fullyFlippedAndSettled })
        assertEquals(1, minus.count { it.fullyFlippedAndSettled })
        assertEquals(2, (plus + minus).count { it.fullyFlippedAndSettled })
        assertEquals(4, (plus + minus).map { it.marketIndex }.distinct().size)
    }

    @Test fun eightyThresholdIncludesNinetyWithoutRequiringSequentialMinute() {
        val markets = listOf(market(2, false, 1 to 82.0, 11 to -56.0),
            market(3, true, 2 to -91.0, 13 to 54.0))
        assertEquals(1, subject.flipEvents(markets, 80, true).count {
            it.fullyFlippedAndSettled
        })
        assertEquals(1, subject.flipEvents(markets, 80, false).count {
            it.fullyFlippedAndSettled
        })
        assertEquals(1, subject.flipEvents(markets, 90, false).size)
        assertEquals(0, subject.flipEvents(markets, 90, true).size)
    }

    @Test fun summaryDisplaysBothDirectionalAndCombinedReversals() {
        val markets = listOf(
            market(1, false, 1 to 94.0, 8 to -56.0),
            market(2, true, 2 to -95.0, 10 to 53.0)
        )
        val report = subject.report(markets, emptyMap())
        assertTrue(report.contains("MAIN RESULT: EXTREME TOUCH"))
        assertTrue(report.contains("90,COMBINED_UNIQUE_MARKETS,2,2,100.0"))
        assertTrue(report.contains("90,FIRST_EXTREME_ONLY,2,2,100.0"))
        assertTrue(report.contains("80,UP_TOUCH_then_DOWN"))
        assertTrue(report.contains("80,DOWN_TOUCH_then_UP"))
        assertTrue(report.contains("NO fixed gap"))
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
