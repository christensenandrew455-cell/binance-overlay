package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcEightPatternReportTest {
    private fun market(id: Long, up: Boolean, vararg readings: Pair<Int, Double>) =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L, 100.0, if (up) 101.0 else 99.0,
            readings.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) }
        )

    @Test fun onlyFourSelectedResearchPatternsAreReported() {
        assertEquals(listOf(
            "-50 -> -60 in 1 min", "-50 -> -60 in 2 min",
            "+50 -> +60 in 1 min", "+50 -> +60 in 2 min"
        ), BtcEightPatternReport.rules.map { it.name })
        assertEquals(listOf("DOWN", "DOWN", "UP", "UP"),
            BtcEightPatternReport.rules.map { it.side })
    }

    @Test fun reportIncludesBothDirectionsAndCorrectExactTime() {
        val markets = listOf(
            market(1, false, 1 to -55.0, 2 to -65.0),
            market(2, true, 1 to -55.0, 2 to -65.0),
            market(3, true, 1 to 55.0, 2 to 65.0),
            market(4, false, 1 to 55.0, 2 to 65.0)
        )
        val report = BtcEightPatternReport.report(markets)
        assertTrue(report.contains("FOUR SELECTED BTC PATTERNS"))
        assertTrue(report.contains("-50 -> -60 in 1 min | BUY DOWN | hit 2/4 (50.0%) | win 1/2 (50.0%)"))
        assertTrue(report.contains("+50 -> +60 in 1 min | BUY UP | hit 2/4 (50.0%) | win 1/2 (50.0%)"))
        assertTrue(report.contains("14 -> 13 min left"))
        assertTrue(report.contains("-50,-60,1,DOWN,14,13,2,50.0,1,50.0"))
        assertFalse(report.contains("-> -50 in 4 min"))
    }
}
