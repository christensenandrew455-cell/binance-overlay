package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcExhaustivePatternReportTest {
    private fun market(id: Long, up: Boolean, vararg points: Pair<Int, Double>) =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L, 100.0, if (up) 101.0 else 99.0,
            points.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) }
        )

    @Test fun signedBinsAreNotConfusedWithOddsOrOtherSide() {
        assertEquals(90, BtcExhaustivePatternReport.band(90.0))
        assertEquals(80, BtcExhaustivePatternReport.band(88.9))
        assertEquals(70, BtcExhaustivePatternReport.band(70.0))
        assertEquals(50, BtcExhaustivePatternReport.band(55.0))
        assertEquals(-50, BtcExhaustivePatternReport.band(-55.0))
        assertEquals(-70, BtcExhaustivePatternReport.band(-78.0))
        assertEquals(-90, BtcExhaustivePatternReport.band(-90.0))
        assertNull(BtcExhaustivePatternReport.band(0.0))
        assertNull(BtcExhaustivePatternReport.band(Double.NaN))
    }

    @Test fun exactGapAndEarliestMarketHitCountOnce() {
        val first = market(1, true,
            1 to 90.0, 2 to 75.0, 3 to 90.0, 4 to 75.0)
        val second = market(2, false,
            1 to 90.0, 3 to 75.0)
        val scan = BtcExhaustivePatternReport.analyze(listOf(first, second))
        val one = BtcExhaustivePatternReport.Path(90, 70, 1)
        val two = BtcExhaustivePatternReport.Path(90, 70, 2)
        assertEquals(1, scan.byPath.getValue(one).size)
        assertEquals(13, scan.byPath.getValue(one).single().finishLeft)
        assertEquals(2, scan.byMinute.filterKeys { it.path == one }.size)
        assertEquals(1, scan.byPath.getValue(two).size)
        assertFalse(scan.byPath.getValue(two).single().up)
        assertEquals(1, scan.byPath.getValue(
            BtcExhaustivePatternReport.Path(90, 70, 3)).size)
        assertFalse(scan.byPath.containsKey(
            BtcExhaustivePatternReport.Path(90, 70, 4)))
    }

    @Test fun cannotPretendThereWasAnInitialPlus90AtMinute15() {
        val scan = BtcExhaustivePatternReport.analyze(listOf(
            market(1, true, 1 to 90.0, 14 to 70.0)
        ))
        assertEquals(1, scan.byPath.getValue(
            BtcExhaustivePatternReport.Path(90, 70, 13)).size)
        assertFalse(scan.byPath.keys.any { it.gap == 14 })
    }

    @Test fun matchesAreDirectionalAndNoFutureInformationEntersEarlierRows() {
        val scan = BtcExhaustivePatternReport.analyze(listOf(
            market(1, false, 3 to -90.0, 5 to -60.0),
            market(2, true, 3 to 90.0, 5 to 60.0)
        ))
        val negative = scan.byPath.getValue(
            BtcExhaustivePatternReport.Path(-90, -60, 2)).single()
        assertFalse(negative.up)
        assertEquals(12, negative.firstLeft)
        assertEquals(10, negative.finishLeft)
        val positive = scan.byPath.getValue(
            BtcExhaustivePatternReport.Path(90, 60, 2)).single()
        assertTrue(positive.up)
    }

    @Test fun reportIncludesAll1400AndExactMinutesAndSampleFlags() {
        val markets = (1L..40L).map { id ->
            market(id, id % 2L == 0L, 1 to 90.0, 2 to 70.0)
        }
        val report = BtcExhaustivePatternReport.report(markets)
        assertTrue(report.contains("10 starting bands x 10 ending bands x 14 delays = 1400"))
        assertTrue(report.contains("INCOMPLETE"))
        assertTrue(report.contains("from_chart,to_chart,gap_minutes,hits"))
        assertTrue(report.contains("+90,+70,1,40,100.0,20,20,50.0,50.0"))
        assertTrue(report.contains("+90,+70,14,0,0.0,0,0,n/a,n/a"))
        assertTrue(report.contains("+90,+70,1,14,13,40,100.0,20,20"))
        assertTrue(report.contains("No pattern has 30+ older hits"))
        assertTrue(report.contains("RESEARCH ONLY"))
    }
}
