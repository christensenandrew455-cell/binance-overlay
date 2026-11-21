package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcContrarianReversalResearchReportTest {
    private val subject = BtcContrarianReversalResearchReport
    private fun market(id: Long, up: Boolean, vararg points: Pair<Int, Double>) =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L, 100.0, if (up) 101.0 else 99.0,
            points.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) }
        )

    @Test fun coversTenPointReversalsExtremesAndRequestedCrossing() {
        assertEquals(24, subject.setups.size)
        assertTrue(subject.setups.any { it.from == 60 && it.to == 50 && it.gap == 1 && !it.buyUp })
        assertTrue(subject.setups.any { it.from == -60 && it.to == -50 && it.gap == 2 && it.buyUp })
        assertTrue(subject.setups.any { it.from == null && it.to == 90 && !it.buyUp })
        assertTrue(subject.setups.any { it.from == -60 && it.to == +50 && it.gap == 1 && !it.buyUp })
    }

    @Test fun reverseWinningSideUsesSettlementNotCurrentPriceDirection() {
        val a = market(1, false, 1 to 64.0, 2 to 54.0)
        val b = market(2, true, 1 to 64.0, 2 to 54.0)
        val c = market(3, false, 1 to 64.0, 3 to 54.0)
        val setup = subject.setups.first { it.from == 60 && it.to == 50 && it.gap == 1 }
        val outcomes = subject.outcomes(listOf(a, b, c), emptyMap(), setup,
            BtcEarlyIndicatorResearchReport.Gate.NONE)
        assertEquals(2, outcomes.size)
        assertEquals(1, outcomes.count { it.won })
        assertEquals(13, outcomes.first().minutesLeft)
    }

    @Test fun singleIndicatorsDoNotChangeUnfilteredCounts() {
        val a = market(1, false, 1 to 94.0, 2 to 84.0)
        val b = market(2, false, 1 to 94.0, 2 to 84.0)
        val setup = subject.setups.first { it.from == 90 && it.to == 80 && it.gap == 1 }
        val feats = mapOf(
            a.startMs to mapOf(2 to BtcEarlyIndicatorResearchReport.Features(0.8, 0.1, -0.2)),
            b.startMs to mapOf(2 to BtcEarlyIndicatorResearchReport.Features(1.5, 0.1, -0.2))
        )
        assertEquals(2, subject.outcomes(listOf(a,b), feats, setup,
            BtcEarlyIndicatorResearchReport.Gate.NONE).size)
        assertEquals(1, subject.outcomes(listOf(a,b), feats, setup,
            BtcEarlyIndicatorResearchReport.Gate.VOLUME).size)
    }

    @Test fun reportNeverConflatesProxyIndexAndRealMarketOdds() {
        val markets = listOf(
            market(1, false, 1 to 94.0, 2 to 84.0),
            market(2, true, 1 to 94.0, 2 to 84.0),
            market(3, false, 1 to -65.0, 2 to 55.0)
        )
        val report = subject.report(markets, emptyMap())
        assertTrue(report.contains("RESEARCH ONLY"))
        assertTrue(report.contains("not Kalshi YES/NO prices"))
        assertTrue(report.contains("MAIN GRAPH AND ALERTS UNCHANGED"))
        assertTrue(report.contains("-60 to +50 in 1m"))
        assertTrue(report.contains("BREAK_EVEN_ASK") || report.contains("break_even_ask"))
    }
}
