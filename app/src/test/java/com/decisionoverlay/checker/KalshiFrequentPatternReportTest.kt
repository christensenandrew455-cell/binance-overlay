package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiFrequentPatternReportTest {
    private fun quote(left: Int, mid: Double): KalshiPatternMath.Quote =
        KalshiPatternMath.Quote(left, (mid - 1.0) / 100.0, (mid + 1.0) / 100.0)

    @Test fun pairMustCompleteInsideEightToFourMinuteWindow() {
        val path = KalshiFrequentPatternReport.PairPath(70, 60)
        assertEquals(7, KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 70.0), quote(7, 60.0)), path)?.left)
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 70.0), quote(3, 60.0)), path))
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 60.0), quote(7, 70.0)), path))
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(7, 70.0), quote(7, 60.0)), path))
    }

    @Test fun completionOutsideWindowRequiresFreshFirstStageToRearm() {
        val path = KalshiFrequentPatternReport.PairPath(70, 60)
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(listOf(
            quote(12, 70.0), quote(11, 60.0),
            quote(10, 65.0), quote(7, 60.0)), path))
        assertEquals(7, KalshiFrequentPatternReport.firstWindowCompletion(listOf(
            quote(12, 70.0), quote(11, 60.0),
            quote(10, 70.0), quote(7, 60.0)), path)?.left)
    }

    @Test fun noEightyToSixtyIsYesTwentyToFortyNotTheWrongSign() {
        val noPath = KalshiFrequentPatternReport.PairPath(20, 40)
        assertEquals("YES 20->40 / NO 80->60", noPath.label())
        assertEquals(6, KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 20.0), quote(6, 40.0)), noPath)?.left)
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 80.0), quote(6, 60.0)), noPath))
    }

    private fun markets(hits: Int): List<KalshiPatternMath.Market> =
        (0 until 1000).map { i ->
            val matches = i < hits
            val candles = if (matches)
                listOf(quote(10, 70.0), quote(7, 60.0))
            else
                listOf(quote(10, 55.0), quote(7, 55.0))
            // When the pattern happens, NO wins in four of every five.
            val yesWon = if (matches) (i % 5 == 0) else (i % 2 == 0)
            KalshiPatternMath.Market("MARKET-" + i, 900L * i, yesWon, candles)
        }

    @Test fun displaysOnlyFrequentAndFeePositiveResultsForBothSides() {
        val report = KalshiFrequentPatternReport.report(markets(200))
        assertTrue(report.contains("1) BUY NO | YES 70->60 / NO 30->40"))
        assertTrue(report.contains("HIT 200/1000 (20.0%)"))
        assertTrue(report.contains("WIN 160/200 (80.0%)"))
        assertTrue(report.contains("8-4 min left"))
        assertTrue(report.contains("Best observed 2-minute range=7-6 min left"))
        assertTrue(report.contains("COMBINED COVERAGE of QUALIFIED setups, distinct markets only: 200/1000"))
        assertFalse(report.contains("1) BUY YES | YES 70->60"))
    }

    @Test fun ninetyNineOfOneThousandNeverAppearsOnLeaderboard() {
        val report = KalshiFrequentPatternReport.report(markets(99))
        assertTrue(report.contains("Frequent patterns (regardless of profit): 0"))
        assertTrue(report.contains("NONE QUALIFIED"))
        assertFalse(report.contains("1) BUY NO | YES 70->60"))
    }
}
