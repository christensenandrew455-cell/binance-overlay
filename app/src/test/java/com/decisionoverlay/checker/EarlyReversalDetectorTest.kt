package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Test

class EarlyReversalDetectorTest {
    private fun candles(up: Boolean): List<Candle> =
        (0 until 8).map { index ->
            val downPhase = index >= 5
            val open = 100.0 + index * 0.2
            val close = if (up) {
                if (downPhase) open + 0.4 else open - 0.1
            } else {
                if (downPhase) open - 0.4 else open + 0.1
            }
            Candle(index.toLong(), open, maxOf(open, close) + 0.2, minOf(open, close) - 0.2,
                close, 10.0, index.toLong(), true)
        }

    @Test fun detectsEarlyDownAboveTargetBeforeEma99TrendWouldNeedToChange() {
        val data = candles(up = false)
        val histogram = listOf(0.0, 1.0, 2.0, 3.0, 2.0, 1.0, 0.0, -1.0)
        val ema = listOf(102.0, 101.9, 101.8, 101.7, 101.6, 101.5, 101.4, 101.3)

        val result = EarlyReversalDetector.evaluate(data, histogram, ema, 90.0)

        assertEquals("EARLY DOWN TREND", result.label)
        assertEquals("ABOVE TARGET", result.targetPosition)
    }

    @Test fun detectsEarlyUpBelowTarget() {
        val data = candles(up = true)
        val histogram = listOf(0.0, -1.0, -2.0, -3.0, -2.0, -1.0, 0.0, 1.0)
        val ema = listOf(100.0, 99.9, 99.8, 99.7, 99.8, 99.9, 100.0, 100.1)

        val result = EarlyReversalDetector.evaluate(data, histogram, ema, 110.0)

        assertEquals("EARLY UP TREND", result.label)
        assertEquals("BELOW TARGET", result.targetPosition)
    }
}
