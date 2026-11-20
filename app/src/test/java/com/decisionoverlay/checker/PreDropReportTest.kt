package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreDropReportTest {
    private fun episode(id: Long, down: Boolean, drop: Int) =
        BinanceTwentySevenPatternReport.Market(id, 100.0, if (down) 99.0 else 101.0,
            (1..14).map { minute ->
                val level = if (minute < 4 || minute >= drop) -60.0 else 65.0
                BinanceTwentySevenPatternReport.Observation(minute, level)
            })

    @Test fun dropColumnIsBlankAndValidationIncludesUpFinishes() {
        val markets = (1L..7L).map { episode(it, true, 6) } +
            listOf(episode(8, false, 6), episode(9, true, 15), episode(10, true, 4))
        val report = BinanceTwentySevenPatternReport.preDropReport(markets)
        val cells = report.lineSequence().first { it.startsWith("discovery,") }.split(',')
        assertEquals(15, cells.size)
        assertEquals(listOf("6", "-60.0", "65.0", "65.0", ""), cells.subList(2, 7))
        assertTrue(report.contains("Triggers: 2/3 (66.7%)."))
        assertTrue(report.contains("Correct DOWN finishes: 1/2 (50.0%)."))
        assertTrue(report.lineSequence().any { it.startsWith("holdout,") && it.contains(",15,") })
    }

    @Test fun noQualifyingTrainingRowsProducesNoSignal() {
        val report = BinanceTwentySevenPatternReport.preDropReport(
            (1L..10L).map { episode(it, true, 4) })
        assertTrue(report.contains("No qualifying discovery paths."))
    }
}
