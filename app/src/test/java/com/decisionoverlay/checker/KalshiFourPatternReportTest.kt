package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiFourPatternReportTest {
    private fun quote(left: Int, yes: Double, halfSpread: Double = 0.0) =
        KalshiPatternMath.Quote(left,
            (yes - halfSpread) / 100.0, (yes + halfSpread) / 100.0)

    @Test fun allFourMirroredSequencesHaveOrderedDistinctCloses() {
        val rules = KalshiFourPatternReport.rules
        assertEquals(4, rules.size)
        assertEquals(-1, rules[0].buySide)
        assertEquals(1, rules[1].buySide)
        assertEquals(1, rules[2].buySide)
        assertEquals(-1, rules[3].buySide)
        assertEquals(6, KalshiFourPatternReport.firstCompletion(
            listOf(quote(10, 70.0), quote(6, 60.0)), rules[0])?.left)
        assertEquals(6, KalshiFourPatternReport.firstCompletion(
            listOf(quote(10, 30.0), quote(6, 40.0)), rules[1])?.left)
        assertEquals(6, KalshiFourPatternReport.firstCompletion(
            listOf(quote(12, 70.0), quote(9, 50.0), quote(6, 60.0)),
            rules[2])?.left)
        assertEquals(6, KalshiFourPatternReport.firstCompletion(
            listOf(quote(12, 30.0), quote(9, 50.0), quote(6, 40.0)),
            rules[3])?.left)
        assertNull(KalshiFourPatternReport.firstCompletion(
            listOf(quote(10, 70.0), quote(10, 60.0)), rules[0]))
    }

    @Test fun skippedLevelsCountAtObservedPriceRatherThanInventedSixtyCentFill() {
        val r = KalshiFourPatternReport.rules[0]
        val observed = listOf(quote(9, 72.0), quote(7, 40.0, 1.0))
        val completion = KalshiFourPatternReport.firstCompletion(observed, r)
        assertEquals(7, completion?.left)
        // YES bid 39c means observed NO ASK = 61c, NOT 40c.
        assertEquals(0.61, completion!!.entryAsk(-1), 1e-9)
        val stats = KalshiFourPatternReport.Stats()
        stats.add(completion, false, -1)
        assertEquals(61.0, stats.askCents(), 1e-9)
        assertEquals(39.0, stats.edgePp(), 1e-9)
    }

    @Test fun rearmAfterResearchPeriodAndHonorNarrowEntryWindow() {
        val r = KalshiFourPatternReport.rules[0]
        val sequence = listOf(quote(15, 70.0), quote(14, 60.0),
            quote(12, 70.0), quote(10, 60.0))
        assertEquals(10, KalshiFourPatternReport.firstCompletion(
            sequence, r)?.left)
        assertNull(KalshiFourPatternReport.firstCompletion(
            sequence, r, KalshiFourPatternReport.Window(5, 3)))
        assertEquals(10, KalshiFourPatternReport.firstCompletion(
            sequence, r, KalshiFourPatternReport.Window(12, 9))?.left)
    }

    @Test fun exactlyTenPercentIsIncludedAndAllFourResultsAlwaysShown() {
        val markets = (0 until 1000).map { i ->
            val candles = if (i < 100)
                listOf(quote(10, 70.0), quote(7, 60.0))
            else listOf(quote(10, 55.0), quote(7, 55.0))
            // 60 of the 100 DOWN signals settle DOWN.
            val yesWon = if (i < 100) i % 10 >= 6 else i % 2 == 0
            KalshiPatternMath.Market("M-" + i, 900L * (i + 100), yesWon, candles)
        }
        val report = KalshiFourPatternReport.report(markets)
        assertTrue(report.contains("YES 70 -> 60 | BUY DOWN"))
        assertTrue(report.contains("YES 30 -> 40 | BUY UP (mirror)"))
        assertTrue(report.contains("YES 70 -> 50 -> 60 | BUY UP"))
        assertTrue(report.contains("YES 30 -> 50 -> 40 | BUY DOWN (mirror)"))
        assertTrue(report.contains("100/1000 | hit=10.0% | wins=60/100 | WR=60.0%"))
        assertTrue(report.contains("20pp + 10% research screen: PASS"))
        assertTrue(report.contains("No hidden eligibility filter removes any of these four results."))
    }

    @Test fun actualNoAskCanDisqualifyEvenWhenHeadlineOddsLookGood() {
        val r = KalshiFourPatternReport.rules[0]
        val q = quote(7, 60.0, 1.0) // actual NO ask 41c
        val older = KalshiFourPatternReport.Stats()
        val all = KalshiFourPatternReport.Stats()
        for (i in 0 until 100) {
            val won = i % 10 >= 6 // YES 40%; DOWN 60%
            older.add(q, won, r.buySide)
            all.add(q, won, r.buySide)
        }
        val score = KalshiFourPatternReport.Score(
            older, KalshiFourPatternReport.Stats(), all)
        assertEquals(19.0, older.edgePp(), 1e-9)
        assertFalse(KalshiFourPatternReport.qualifies(score, 700, 1000))
    }
}
