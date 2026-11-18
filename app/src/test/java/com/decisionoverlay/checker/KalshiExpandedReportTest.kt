package com.decisionoverlay.checker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KalshiExpandedReportTest {
    private fun q(left: Int, yes: Double) =
        KalshiPatternMath.Quote(left, (yes - 1.0) / 100.0, (yes + 1.0) / 100.0)

    @Test fun evaluatesBothSettlementsAfterTheSameRecovery() {
        val path = listOf(q(14, 82.0), q(12, 60.0), q(9, 71.0))
        val markets = (0 until 12).map { i ->
            KalshiPatternMath.Market("M" + i, 900L * (i + 1),
                i >= 8, path)
        }
        val result = KalshiPatternMath.report(markets)
        assertTrue(result.contains("=== EXPANDED BTC 15-MINUTE KALSHI PATH BACKTEST ==="))
        assertTrue(result.contains("80>60>70: n=12/12 (100.0%; ~52.0/day)"))
        assertTrue(result.contains("YES 4/12 33.3%"))
        assertTrue(result.contains("NO 8/12 66.7%"))
        assertTrue(result.contains("PATH 80>60>70"))
        assertTrue(result.contains("12-9m n=12"))
    }

    @Test fun tracksUpwardAndDownwardTransitionsAndNeverUsesSameMinuteTwice() {
        val up = KalshiPatternMath.Market("U", 900L, false,
            listOf(q(14, 60.0), q(12, 72.0)))
        val down = KalshiPatternMath.Market("D", 1800L, true,
            listOf(q(14, 72.0), q(12, 60.0)))
        val result = KalshiPatternMath.report(listOf(up, down))
        assertTrue(result.contains("60>70: n=1/2"))
        assertTrue(result.contains("70>60: n=1/2"))
        assertFalse(result.contains("60>70: n=2/2"))
    }

    @Test fun threeLevelPathRequiresOrderedObservations() {
        val noRecovery = KalshiPatternMath.Market("X", 900L, false,
            listOf(q(14, 82.0), q(13, 71.0), q(12, 60.0)))
        val result = KalshiPatternMath.report(listOf(noRecovery))
        assertTrue(result.contains("80>60: n=1/1"))
        assertFalse(result.contains("80>60>70: n=1/1"))
    }
}
