package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiEdgeResearchReportTest {
    private fun q(left: Int, yesPct: Double) =
        KalshiPatternMath.Quote(left, (yesPct - 1.0) / 100.0,
            (yesPct + 1.0) / 100.0)

    @Test fun exactlySevenAreTestedAndMirrorDoesNotCreateExtraMarkets() {
        val a = KalshiPatternMath.Market("A", 900, true,
            listOf(q(9, 40.0), q(8, 60.0), q(7, 65.0)))
        val report = KalshiEdgeResearchReport.report(listOf(a))
        assertTrue(report.contains("Original seven: 1/1 (100.0% combined hit)"))
        assertTrue(report.contains("YES 40->60 = NO 60->40 [same event; UP TREND]"))
        assertTrue(report.contains("YES wins=1/1 (100.0%) NO wins=0/1 (0.0%)"))
        assertTrue(report.contains("8m: n=1/1"))
        assertTrue(report.contains("NO profitability, fees, or expected-return estimates are used or shown."))
    }

    @Test fun oppositesRetainCorrectSettlementAndPriceGap() {
        val a = KalshiPatternMath.Market("A", 900, false,
            listOf(q(9, 80.0), q(8, 70.0)))
        val report = KalshiEdgeResearchReport.report(listOf(a))
        assertTrue(report.contains("YES 80->70 = NO 20->30"))
        assertTrue(report.contains("YES wins=0/1 (0.0%) NO wins=1/1 (100.0%)"))
        assertTrue(report.contains("historical-minus-QUOTE YES=-70.0pp NO=+70.0pp"))
    }

    @Test fun firstCompletionRequiresOrderedSeparateMinutes() {
        val a = KalshiPatternMath.Market("A", 900, true,
            listOf(q(8, 60.0), q(7, 40.0)))
        val report = KalshiEdgeResearchReport.report(listOf(a))
        assertTrue(report.contains("Original seven: 0/1 (0.0% combined hit)"))
        assertTrue(report.contains("YES 40->60 = NO 60->40"))
    }
}
