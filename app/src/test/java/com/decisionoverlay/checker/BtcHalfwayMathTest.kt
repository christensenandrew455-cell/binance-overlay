package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcHalfwayMathTest {
    @Test fun separatePlusMinusCasesUseExplicitGapTolerance() {
        assertTrue(BtcHalfwayMath.matches(100.375, 100.0, true))
        assertTrue(BtcHalfwayMath.matches(99.625, 100.0, false))
        assertTrue(BtcHalfwayMath.matches(100.35, 100.0, true))
        assertTrue(BtcHalfwayMath.matches(100.40, 100.0, true))
        assertFalse(BtcHalfwayMath.matches(100.45, 100.0, true))
        assertFalse(BtcHalfwayMath.matches(100.375, 100.0, false))
        assertFalse(BtcHalfwayMath.matches(100.0, 100.0, true))
        assertEquals(.375, BtcHalfwayMath.gap(100.375, 100.0), .000001)
    }
    @Test fun experimentYesMeansStayOnObservedSideNotKalshiYes() {
        assertEquals("STAY", BtcHalfwayMath.result(101.0, 100.0, true))
        assertEquals("STAY", BtcHalfwayMath.result(99.0, 100.0, false))
        assertEquals("CROSS", BtcHalfwayMath.result(99.0, 100.0, true))
        assertEquals("CROSS", BtcHalfwayMath.result(101.0, 100.0, false))
        assertEquals("TIE", BtcHalfwayMath.result(100.0, 100.0, true))
        assertEquals("TIE", BtcHalfwayMath.result(100.0, 100.0, false))
    }
}