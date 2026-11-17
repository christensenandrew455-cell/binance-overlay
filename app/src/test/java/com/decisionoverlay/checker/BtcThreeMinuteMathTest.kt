package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcThreeMinuteMathTest {
    @Test fun observationIsExactlyThreeMinutesBeforeSettlement() {
        assertEquals(12, BtcThreeMinuteMath.ELAPSED)
        assertEquals(3, 15 - BtcThreeMinuteMath.ELAPSED)
    }

    @Test fun gapMatchesAroundObservedSixtySixDollarDownMove() {
        val target = 64999.0
        val spot = 64933.0
        assertTrue(BtcThreeMinuteMath.matches(spot, target, false))
        assertFalse(BtcThreeMinuteMath.matches(spot, target, true))
        assertEquals(-66.0 / 64999.0 * 100.0,
            BtcThreeMinuteMath.gap(spot, target), 1e-9)
    }

    @Test fun samplesOnlyFallWithinDisclosedBandOnCorrectSide() {
        assertTrue(BtcThreeMinuteMath.matches(100.09, 100.0, true))
        assertTrue(BtcThreeMinuteMath.matches(100.11, 100.0, true))
        assertTrue(BtcThreeMinuteMath.matches(99.90, 100.0, false))
        assertFalse(BtcThreeMinuteMath.matches(100.089, 100.0, true))
        assertFalse(BtcThreeMinuteMath.matches(100.111, 100.0, true))
        assertFalse(BtcThreeMinuteMath.matches(100.0, 100.0, true))
        assertFalse(BtcThreeMinuteMath.matches(Double.NaN, 100.0, false))
    }

    @Test fun closeOnOriginalSideVersusReversalVersusTie() {
        assertEquals("STAY", BtcThreeMinuteMath.result(64900.0, 64999.0, false))
        assertEquals("CROSS", BtcThreeMinuteMath.result(65030.0, 64999.0, false))
        assertEquals("STAY", BtcThreeMinuteMath.result(65030.0, 64999.0, true))
        assertEquals("CROSS", BtcThreeMinuteMath.result(64900.0, 64999.0, true))
        assertEquals("TIE", BtcThreeMinuteMath.result(64999.0, 64999.0, false))
    }
}
