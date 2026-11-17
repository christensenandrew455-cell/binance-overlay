package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcFunnelMathTest {
    @Test fun funnelThresholdIsFiveHundredthsPercentPerMinuteLeft() {
        val expected = listOf(0.40, 0.35, 0.30, 0.25, 0.20, 0.15, 0.10, 0.05)
        for (elapsed in 7..14) {
            assertEquals(expected[elapsed - 7], BtcFunnelMath.threshold(elapsed), 1e-9)
        }
    }

    @Test fun matchesNarrowBandAndSeparatesSideAtEveryMinute() {
        val target = 65000.0
        for (elapsed in 7..14) {
            val line = BtcFunnelMath.threshold(elapsed)
            val up = target * (1 + line / 100.0)
            val down = target * (1 - line / 100.0)
            assertTrue(BtcFunnelMath.matches(up, target, elapsed, true))
            assertFalse(BtcFunnelMath.matches(up, target, elapsed, false))
            assertTrue(BtcFunnelMath.matches(down, target, elapsed, false))
            assertFalse(BtcFunnelMath.matches(down, target, elapsed, true))
            assertFalse(BtcFunnelMath.matches(target * (1 + (line + 0.011)/100),
                target, elapsed, true))
            assertFalse(BtcFunnelMath.matches(target * (1 - (line + 0.011)/100),
                target, elapsed, false))
        }
    }

    @Test fun rejectedValuesAndTiesDoNotQualify() {
        assertFalse(BtcFunnelMath.matches(Double.NaN, 65000.0, 7, true))
        assertFalse(BtcFunnelMath.matches(Double.POSITIVE_INFINITY, 65000.0, 7, true))
        assertFalse(BtcFunnelMath.matches(65000.0, 65000.0, 14, false))
        assertFalse(BtcFunnelMath.matches(65000.0, 0.0, 14, true))
    }

    @Test fun endOnOriginalSideIsDifferentFromCrossOrTie() {
        assertEquals("STAY", BtcFunnelMath.result(64900.0, 65000.0, false))
        assertEquals("CROSS", BtcFunnelMath.result(65100.0, 65000.0, false))
        assertEquals("STAY", BtcFunnelMath.result(65100.0, 65000.0, true))
        assertEquals("CROSS", BtcFunnelMath.result(64900.0, 65000.0, true))
        assertEquals("TIE", BtcFunnelMath.result(65000.0, 65000.0, true))
    }
}
