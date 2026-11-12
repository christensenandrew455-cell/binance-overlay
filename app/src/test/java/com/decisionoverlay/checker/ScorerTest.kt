package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IndicatorsTest {
    @Test fun emaThreeUsesThreePeriodMultiplier() {
        val values = listOf(10.0, 20.0, 30.0)
        val result = Indicators.emaSeriesFromFirst(values, 3)

        // EMA(3) multiplier is 2 / (3 + 1) = 0.5.
        assertEquals(listOf(10.0, 15.0, 22.5), result)
    }

    @Test fun emaSevenRemainsAvailableForPrice() {
        val values = listOf(10.0, 20.0)
        val result = Indicators.emaSeriesFromFirst(values, 7)

        assertEquals(10.0, result[0], 0.000001)
        assertEquals(12.5, result[1], 0.000001)
    }

    @Test fun emptyInputReturnsEmptySeries() {
        assertTrue(Indicators.emaSeriesFromFirst(emptyList(), 3).isEmpty())
    }
}
