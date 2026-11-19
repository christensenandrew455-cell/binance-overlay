package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiFrequentPatternReportTest {
    private fun quote(left: Int, mid: Double): KalshiPatternMath.Quote =
        KalshiPatternMath.Quote(left, (mid - 1.0) / 100.0, (mid + 1.0) / 100.0)

    @Test fun pairMustCompleteInsideEightToFourMinuteWindow() {
        val path = KalshiFrequentPatternReport.PairPath(50, 40)
        assertEquals(7, KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 50.0), quote(7, 40.0)), path)?.left)
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 50.0), quote(3, 40.0)), path))
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 40.0), quote(7, 50.0)), path))
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(7, 50.0), quote(7, 40.0)), path))
    }

    @Test fun completionOutsideWindowRequiresFreshFirstStageToRearm() {
        val path = KalshiFrequentPatternReport.PairPath(50, 40)
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(listOf(
            quote(12, 50.0), quote(11, 40.0),
            quote(10, 45.0), quote(7, 40.0)), path))
        assertEquals(7, KalshiFrequentPatternReport.firstWindowCompletion(listOf(
            quote(12, 50.0), quote(11, 40.0),
            quote(10, 50.0), quote(7, 40.0)), path)?.left)
    }

    @Test fun noEightyToSixtyIsYesTwentyToFortyNotWrongSide() {
        val noPath = KalshiFrequentPatternReport.PairPath(20, 40)
        assertEquals("YES 20->40 / NO 80->60", noPath.label())
        assertEquals(6, KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 20.0), quote(6, 40.0)), noPath)?.left)
        assertNull(KalshiFrequentPatternReport.firstWindowCompletion(
            listOf(quote(10, 80.0), quote(6, 60.0)), noPath))
    }

    private fun markets(
        hits: Int, first: Int = 50, second: Int = 40, noWinsPerTen: Int = 7
    ): List<KalshiPatternMath.Market> =
        (0 until 1000).map { i ->
            val match = i < hits
            val candles = if (match)
                listOf(quote(10, first.toDouble()), quote(7, second.toDouble()))
            else listOf(quote(10, 55.0), quote(7, 55.0))
            val yesWon = if (match) (i % 10 >= noWinsPerTen) else i % 2 == 0
            KalshiPatternMath.Market("MARKET-" + i, 900L * i, yesWon, candles)
        }

    @Test fun exampleFiftyToFortyBuyDownWinSeventyHitTwenty() {
        val report = KalshiFrequentPatternReport.report(markets(200))
        assertTrue(report.contains("1) 50% - 40%"))
        assertTrue(report.contains("Buy: DOWN"))
        assertTrue(report.contains("WR: 70.0% (140/200)"))
        assertTrue(report.contains("Hit rate: 20.0% (200/1000)"))
        assertTrue(report.contains("Best timeframe: 8 to 4 mins left") ||
            report.contains("Best timeframe: 8 to 7 mins left") ||
            report.contains("Best timeframe: 7 to 6 mins left"))
        assertTrue(report.contains("MATCHING PATTERNS: 1"))
    }

    @Test fun hitRateMustBeStrictlyAboveTenPercent() {
        val tenPercent = KalshiFrequentPatternReport.report(markets(100))
        assertTrue(tenPercent.contains("MATCHING PATTERNS: 0"))
        assertTrue(tenPercent.contains("NONE MATCHED BOTH"))
        val aboveTen = KalshiFrequentPatternReport.report(markets(101))
        assertTrue(aboveTen.contains("MATCHING PATTERNS: 1"))
        assertTrue(aboveTen.contains("Hit rate: 10.1% (101/1000)"))
    }

    @Test fun exactlyTwentyPointRawGapIsIncludedEvenWhenNetMayBeNegative() {
        val exactlyTwenty = KalshiFrequentPatternReport.report(
            markets(200, noWinsPerTen = 6))
        assertTrue(exactlyTwenty.contains("MATCHING PATTERNS: 1"))
        assertTrue(exactlyTwenty.contains("Buy: DOWN"))
        assertTrue(exactlyTwenty.contains("WR: 60.0% (120/200)"))
    }

    @Test fun underTwentyPointRawGapIsExcluded() {
        val markets = (0 until 1000).map { i ->
            val match = i < 200
            KalshiPatternMath.Market("T-" + i, 900L * i,
                if (match) i >= 119 else i % 2 == 0,
                if (match) listOf(quote(10, 50.0), quote(7, 40.0))
                else listOf(quote(10, 55.0), quote(7, 55.0)))
        }
        val report = KalshiFrequentPatternReport.report(markets)
        assertTrue(report.contains("MATCHING PATTERNS: 0"))
    }

    @Test fun highEndPriceCanProduceBuyDownWithLowerWinRate() {
        val report = KalshiFrequentPatternReport.report(
            markets(200, first = 80, second = 70, noWinsPerTen = 4))
        assertTrue(report.contains("1) 80% - 70%"))
        assertTrue(report.contains("Buy: DOWN"))
        assertTrue(report.contains("WR: 40.0% (80/200)"))
        assertTrue(report.contains("Hit rate: 20.0% (200/1000)"))
    }

    @Test fun bestTimeframeFavorsHighestWinRateWithTenPercentHitMinimum() {
        val markets = (0 until 1000).map { i ->
            val match = i < 200
            val firstGroup = i < 110
            val minute = if (firstGroup) 8 else 4
            val noWon = if (firstGroup) i % 10 != 0 else i % 10 < 4
            KalshiPatternMath.Market("T-" + i, 900L * i,
                if (match) !noWon else i % 2 == 0,
                if (match) listOf(quote(10, 50.0), quote(minute, 40.0))
                else listOf(quote(10, 55.0), quote(7, 55.0)))
        }
        val report = KalshiFrequentPatternReport.report(markets)
        assertTrue(report.contains("MATCHING PATTERNS: 1"))
        assertTrue(report.contains("Buy: DOWN"))
        assertTrue(report.contains("WR: 90.0% (99/110)"))
        assertTrue(report.contains("Hit rate: 11.0% (110/1000)"))
        assertTrue(report.contains("Best timeframe: 8 to 7 mins left"))
    }

    @Test fun nonMatchingPairsDoNotCountAsHitOrIndependentMirror() {
        val report = KalshiFrequentPatternReport.report(markets(0))
        assertTrue(report.contains("MATCHING PATTERNS: 0"))
        assertTrue(report.contains("NONE MATCHED BOTH"))
    }
}
