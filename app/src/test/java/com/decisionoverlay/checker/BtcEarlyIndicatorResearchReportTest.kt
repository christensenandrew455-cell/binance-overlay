package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcEarlyIndicatorResearchReportTest {
    private val test = BtcEarlyIndicatorResearchReport

    private fun market(id: Long, up: Boolean, vararg points: Pair<Int, Double>) =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L, 100.0, if (up) 101.0 else 99.0,
            points.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) }
        )

    @Test fun closedCandleFeaturesNeverReadFutureBars() {
        val start = 1000L * 60_000L
        val candles = (-20..14).associate { minute ->
            val at = start + minute * 60_000L
            at to BtcAcmMath.Candle(at, 100.0 + minute, 102.0 + minute,
                99.0 + minute, 101.0 + minute, 10.0)
        }.toMutableMap()
        val original = test.featuresAt(start, 3, candles)
        assertNotNull(original)
        assertEquals(1.0, original!!.relVolume, 0.00001)
        val futureAt = start + 8 * 60_000L
        candles[futureAt] = BtcAcmMath.Candle(futureAt, 200.0, 220.0,
            195.0, 218.0, 1_000_000.0)
        assertEquals(original, test.featuresAt(start, 3, candles))
    }

    @Test fun newCandidatesKeepOriginalFourUntouched() {
        assertEquals(16, test.candidates.size)
        assertEquals(4, test.candidates.count { it.existing })
        assertTrue(test.candidates.any { it.from == -70 && it.to == -60 && it.gap == 1 })
        assertTrue(test.candidates.any { it.from == +70 && it.to == +60 && it.gap == 2 })
        assertEquals(4, test.leads.size)
    }

    @Test fun filtersApplyOnlyAtCompletedSignalNotFutureOutcome() {
        val m1 = market(1, false, 1 to -75.0, 2 to -65.0, 3 to -55.0)
        val m2 = market(2, true, 1 to -75.0, 2 to -65.0)
        val m3 = market(3, false, 1 to -75.0, 2 to -75.0, 3 to -75.0)
        val markets = listOf(m1, m2, m3)
        val features = mapOf(
            m1.startMs to mapOf(1 to BtcEarlyIndicatorResearchReport.Features(1.5, -0.3, -0.1),
                2 to BtcEarlyIndicatorResearchReport.Features(1.5, -0.5, -0.2)),
            m2.startMs to mapOf(1 to BtcEarlyIndicatorResearchReport.Features(0.8, 0.1, 0.1),
                2 to BtcEarlyIndicatorResearchReport.Features(0.8, 0.1, 0.1)),
            m3.startMs to mapOf(1 to BtcEarlyIndicatorResearchReport.Features(1.5, -0.4, -0.2))
        )
        val path = test.candidates.first {
            it.from == -70 && it.to == -60 && it.gap == 1
        }
        val baseline = test.pathOutcomes(markets, features, path, BtcEarlyIndicatorResearchReport.Gate.NONE)
        val filtered = test.pathOutcomes(markets, features, path, BtcEarlyIndicatorResearchReport.Gate.VOL_MOM)
        assertEquals(2, baseline.size)
        assertEquals(1, filtered.size)
        assertEquals(13, filtered.single().minutesLeft)
        assertTrue(filtered.single().won)

        val lead = test.leads.first { it.from == -70 }
        val leadBaseline = test.leadOutcomes(markets, features, lead, BtcEarlyIndicatorResearchReport.Gate.NONE)
        val leadFilter = test.leadOutcomes(markets, features, lead, BtcEarlyIndicatorResearchReport.Gate.VOL_MOM)
        assertEquals(3, leadBaseline.size)
        assertEquals(2, leadFilter.size)
        assertEquals(2, leadBaseline.count { it.reached })
        assertEquals(1, leadFilter.count { it.reached })
        assertEquals(14, leadFilter.first().minutesLeft)
    }

    @Test fun reportIsClearlyResearchOnlyAndHasHoldoutAndNextBandOutput() {
        val markets = listOf(
            market(1, false, 1 to -75.0, 2 to -65.0),
            market(2, true, 1 to +75.0, 2 to +65.0)
        )
        val report = test.report(markets, emptyMap())
        assertTrue(report.contains("RESEARCH ONLY"))
        assertTrue(report.contains("newest 1 hold out"))
        assertTrue(report.contains("BEFORE THE MOVEMENT"))
        assertTrue(report.contains("NO Kalshi price"))
        assertTrue(report.contains("-70 to -60 in 1m"))
        assertTrue(report.contains("+70 predicts +60"))
    }
}
