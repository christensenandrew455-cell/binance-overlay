package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcFunnelLevelsTest {
    @Test fun bothSidesHaveSameHypothesizedCeilingAtAllFifteenMinutes() {
        for (left in 1..15) {
            val pct = left * 0.05
            assertEquals(pct, BtcFunnelLevels.ceilingPct(left), 1e-9)
            assertEquals(65000.0 * (1 + pct / 100),
                BtcFunnelLevels.priceAt(65000.0, pct, 1), 1e-8)
            assertEquals(65000.0 * (1 - pct / 100),
                BtcFunnelLevels.priceAt(65000.0, pct, -1), 1e-8)
        }
        assertTrue(BtcFunnelLevels.withinCeiling(0.25, 5))
        assertFalse(BtcFunnelLevels.withinCeiling(0.26, 5))
    }

    @Test fun linesUseOnlyOlderTrainingForCandidateAndThenValidate() {
        val keyOne = HoldProbabilityEngine.Key(5, 1, 1)
        val keyTwo = HoldProbabilityEngine.Key(5, 2, 1)
        val hist = HoldProbabilityEngine.Historical("BTC", 4800, 2400, 2400,
            0, 100000L,
            mapOf(keyOne to HoldProbabilityEngine.Rate(120, 75),
                  keyTwo to HoldProbabilityEngine.Rate(120, 105)),
            mapOf(keyOne to HoldProbabilityEngine.Rate(120, 80),
                  keyTwo to HoldProbabilityEngine.Rate(120, 98)))
        val sixty = BtcFunnelLevels.findLevel(hist, 5, 1, 60)
        assertNotNull(sixty)
        assertEquals(0.01, sixty!!.gapPct, 1e-9)
        assertTrue(sixty.validated)
        val eighty = BtcFunnelLevels.findLevel(hist, 5, 1, 80)
        assertNotNull(eighty)
        assertEquals(0.02, eighty!!.gapPct, 1e-9)
        assertTrue(eighty.validated)
        assertNull(BtcFunnelLevels.findLevel(hist, 5, -1, 60))
        assertNull(BtcFunnelLevels.findLevel(hist, 15, 1, 60))
    }

    @Test fun quoteMustClearConservativeHeldOutBoundAfterFees() {
        val sample = HoldProbabilityEngine.Rate(300, 285)
        val level = BtcFunnelLevels.Level(80, 0.10,
            sample, sample.copy(), true)
        assertEquals("BELOW CONSERVATIVE HISTORICAL BOUND",
            BtcFunnelLevels.comparison(level, .60))
        assertEquals("KALSHI PRICE TOO HIGH FOR THIS HISTORICAL LEVEL",
            BtcFunnelLevels.comparison(level, .98))
        assertEquals("NO KALSHI ASK", BtcFunnelLevels.comparison(level, null))
        assertEquals("NOT VALIDATED",
            BtcFunnelLevels.comparison(level.copy(validated = false), .10))
    }
}
