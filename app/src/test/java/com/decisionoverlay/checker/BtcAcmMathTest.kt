package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcAcmMathTest {
    private fun bar(index: Int, range: Double): BtcAcmMath.Candle {
        val at = index * BtcAcmMath.MINUTE_MS
        return BtcAcmMath.Candle(at, 100.0,
            100.0 + range / 2.0, 100.0 - range / 2.0, 100.0)
    }

    @Test fun quarterTargetUsesExactPriorMinuteHighLowNotClose() {
        val step = BtcAcmMath.MINUTE_MS
        assertEquals("BTCUSD", BtcAcmMath.BTC_SYMBOL)
        // Include quarter rollover and all four boundaries, with deliberately
        // different neighboring candles and a close unlike the midpoint.
        for (start in listOf(0L, 15L, 30L, 45L, 60L).map { it * step }) {
            val prior = BtcAcmMath.Candle(start - step, 60886.09,
                60909.02, 60863.11, 60907.29)
            val earlier = BtcAcmMath.Candle(start - 2 * step, 60961.76,
                60970.0, 60950.0, 60961.76)
            val current = BtcAcmMath.Candle(start, 60960.0,
                61000.0, 60950.0, 60961.76)
            val candles = listOf(earlier, prior, current).associateBy { it.at }
            val chosen = BtcAcmMath.quarterTargetCandle(candles, start)!!
            assertEquals(start - step, chosen.at)
            assertEquals(60886.065, chosen.midpoint, 1e-8)
            assertNull(BtcAcmMath.quarterTargetCandle(candles - prior.at, start))
        }
    }

    @Test fun lastThreeFifteenThirtySixtyOnlyIncludeCompletedCandles() {
        val candles = (0..59).associate { i ->
            val c = bar(i, if (i >= 57) 6.0 else 2.0)
            c.at to c
        }.toMutableMap()
        val last = 59L * BtcAcmMath.MINUTE_MS
        assertEquals(6.0, BtcAcmMath.averageRange(candles, last, 3)!!, 1e-9)
        assertEquals(2.8, BtcAcmMath.averageRange(candles, last, 15)!!, 1e-9)
        assertEquals(2.4, BtcAcmMath.averageRange(candles, last, 30)!!, 1e-9)
        assertEquals(2.2, BtcAcmMath.averageRange(candles, last, 60)!!, 1e-9)

        val future = bar(60, 100.0)
        candles[future.at] = future
        assertEquals(2.2, BtcAcmMath.averageRange(candles, last, 60)!!, 1e-9)
        candles.remove(20L * BtcAcmMath.MINUTE_MS)
        assertNull(BtcAcmMath.averageRange(candles, last, 60))
        assertEquals(6.0, BtcAcmMath.averageRange(candles, last, 3)!!, 1e-9)
    }

    @Test fun triggersOnlyAtOrBeyondFourTimesAverageWhenFourMinutesLeft() {
        assertEquals(4.0, BtcAcmMath.thresholdPct(1.0, 4), 1e-9)
        assertTrue(BtcAcmMath.triggered(104.0, 100.0, 1.0, 4))
        assertTrue(BtcAcmMath.triggered(96.0, 100.0, 1.0, 4))
        assertFalse(BtcAcmMath.triggered(103.99, 100.0, 1.0, 4))
        assertFalse(BtcAcmMath.triggered(96.01, 100.0, 1.0, 4))
        assertFalse(BtcAcmMath.triggered(100.0, 100.0, 0.01, 4))
        assertFalse(BtcAcmMath.triggered(104.0, 100.0, 0.0, 4))
    }

    @Test fun separateStayFromWickTouchAndFinalTie() {
        val future = listOf(BtcAcmMath.Candle(0L, 102.0, 103.0, 99.0, 101.0))
        val up = BtcAcmMath.outcome(1, 100.0, 101.0, future)
        assertTrue(up.stay)
        assertTrue(up.touched)
        assertFalse(up.tied)
        val down = BtcAcmMath.outcome(-1, 100.0, 99.0, future)
        assertTrue(down.stay)
        assertTrue(down.touched)
        val tie = BtcAcmMath.outcome(-1, 100.0, 100.0, future)
        assertFalse(tie.stay)
        assertTrue(tie.tied)
        val safe = BtcAcmMath.outcome(1, 100.0, 101.0,
            listOf(BtcAcmMath.Candle(0L, 102.0, 103.0, 100.1, 101.0)))
        assertTrue(safe.stay)
        assertFalse(safe.touched)
    }
}
