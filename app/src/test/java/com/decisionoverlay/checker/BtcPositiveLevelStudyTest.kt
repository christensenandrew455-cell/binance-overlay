package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcPositiveLevelStudyTest {
    private fun market(
        id: Long, finishUp: Boolean, levels: Map<Int, Double>
    ): BinanceTwentySevenPatternReport.Market =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L,
            100.0,
            if (finishUp) 101.0 else 99.0,
            (1..14).map { minute ->
                BinanceTwentySevenPatternReport.Observation(
                    minute, levels[minute] ?: -65.0
                )
            }
        )

    @Test fun positiveBandsAreExclusiveAndNegativeLevelsCannotPredictUp() {
        assertEquals(50, BtcPositiveLevelStudy.positiveBand(50.5))
        assertEquals(60, BtcPositiveLevelStudy.positiveBand(60.0))
        assertEquals(70, BtcPositiveLevelStudy.positiveBand(79.99))
        assertEquals(80, BtcPositiveLevelStudy.positiveBand(80.1))
        assertEquals(90, BtcPositiveLevelStudy.positiveBand(90.0))
        assertEquals(null, BtcPositiveLevelStudy.positiveBand(-90.0))
        assertEquals(null, BtcPositiveLevelStudy.positiveBand(0.0))
        assertEquals(null, BtcPositiveLevelStudy.positiveBand(Double.NaN))
    }

    @Test fun everyMinuteUsesCurrentScoreNotTheEarlierMaximum() {
        val up = market(1, true, mapOf(1 to 90.0, 2 to 75.0, 3 to 65.0))
        val down = market(2, false, mapOf(1 to 75.0, 2 to 90.0, 3 to 75.0))
        val report = BtcPositiveLevelStudy.report(listOf(up, down))
        assertTrue(report.contains("14,+90,1,1,0,100.0%"))
        assertTrue(report.contains("14,+70,1,0,1,0.0%"))
        assertTrue(report.contains("13,+90,1,0,1,0.0%"))
        assertTrue(report.contains("13,+70,1,1,0,100.0%"))
        assertTrue(report.contains("12,+60,1,1,0,100.0%"))
        assertTrue(report.contains("15,+90,n/a,n/a,n/a,n/a"))
        assertTrue(report.contains("0,+90,n/a,n/a,n/a,n/a"))
    }

    @Test fun firstTouchIsOncePerMarketAndOnlyWhenThresholdWasObserved() {
        val up = market(1, true, mapOf(1 to 90.0, 2 to 90.0, 3 to 90.0))
        val down = market(2, false, mapOf(3 to 90.0, 4 to 90.0))
        val report = BtcPositiveLevelStudy.report(listOf(up, down))
        assertTrue(report.contains("+90,14,1,1,0,100.0%"))
        assertTrue(report.contains("+90,12,1,0,1,0.0%"))
        assertTrue(report.contains("+90,2,0,0,0,n/a"))
        assertTrue(report.contains("+90,2,0,100.0%,2.00,13.00,1,1,50.0%"))
    }

    @Test fun twentyPointDropCanHappenAtAnyLaterMinuteButNotOnSameMinute() {
        val up = market(1, true, mapOf(1 to 90.0, 8 to 69.0, 9 to 50.0))
        val down = market(2, false, mapOf(2 to 90.0, 3 to 85.0, 4 to 72.0))
        val report = BtcPositiveLevelStudy.report(listOf(up, down))
        assertTrue(report.contains("+90 -> <=+70,1,1,0,100.0%,7.00"))
        assertTrue(report.contains("+80 -> <=+60,1,1,0,100.0%,8.00"))
        assertFalse(report.contains("+90 -> <=+70,2,"))
    }

    @Test fun noFutureInformationIsUsedToDescribeEarlyCheckpoint() {
        val m = market(7, true, mapOf(8 to 90.0))
        val report = BtcPositiveLevelStudy.report(listOf(m))
        assertTrue(report.contains("14,+90,0,0,0,n/a"))
        assertTrue(report.contains("7,+90,1,1,0,100.0%"))
        assertTrue(report.contains("+90,7,1,1,0,100.0%"))
    }
}
