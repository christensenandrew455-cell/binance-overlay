package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Test

class EarlyReversalDetectorTest {
    // Eight one-minute candles with a genuine, steady move in the requested direction.
    private fun candles(up: Boolean): List<Candle> =
        (0 until 8).map { index ->
            val close = if (up) 100.0 + index else 100.0 - index
            val open = if (up) close - 0.2 else close + 0.2
            val openTime = index.toLong() * 60_000L
            Candle(
                openTime,
                open,
                maxOf(open, close) + 0.2,
                minOf(open, close) - 0.2,
                close,
                10.0,
                openTime + 59_999L,
                true,
            )
        }

    @Test
    fun detectsEarlyDownWhenActualPriceIsAboveTargetAndCanReachItInTime() {
        val data = candles(up = false)
        val histogram = listOf(0.0, 1.0, 2.0, 3.0, 2.0, 1.0, 0.0, -1.0)
        val ema = listOf(102.0, 101.9, 101.8, 101.7, 101.6, 101.5, 101.4, 101.3)

        // At 10 minutes into the 15-minute window, 5 minutes remain.
        // Actual price falls $1/min from $95 to $93; the lower $88 target is 5 minutes away.
        val result = EarlyReversalDetector.evaluate(data, histogram, ema, 88.0, nowMs = 10L * 60L * 1000L)

        assertEquals("EARLY DOWN TREND", result.label)
        assertEquals("ABOVE TARGET", result.targetPosition)
    }

    @Test
    fun detectsEarlyUpWhenActualPriceIsBelowTargetAndCanReachItInTime() {
        val data = candles(up = true)
        val histogram = listOf(0.0, -1.0, -2.0, -3.0, -2.0, -1.0, 0.0, 1.0)
        val ema = listOf(100.0, 99.9, 99.8, 99.7, 99.8, 99.9, 100.0, 100.1)

        val result = EarlyReversalDetector.evaluate(data, histogram, ema, 112.0, nowMs = 10L * 60L * 1000L)

        assertEquals("EARLY UP TREND", result.label)
        assertEquals("BELOW TARGET", result.targetPosition)
    }

    @Test
    fun evaluatesTrendBeforeFinalFiveMinutes() {
        val data = candles(up = false)
        val histogram = List(8) { -it.toDouble() }
        val ema = List(8) { 100.0 }

        val result = EarlyReversalDetector.evaluate(data, histogram, ema, 98.0, nowMs = 9L * 60L * 1000L)

        org.junit.Assert.assertNotEquals("WAIT • FINAL 5 MINUTES ONLY", result.label)
    }

    @Test
    fun detectsPanicSellingBeingAbsorbedByReplenishingBids() {
        val closes = listOf(110.0, 108.0, 106.0, 104.0, 102.0, 100.0, 99.0, 100.0)
        val data = closes.mapIndexed { index, close ->
            val open = if (index == 7) 99.0 else close + 0.5
            val openTime = index * 60_000L
            Candle(
                openTime, open, maxOf(open, close) + 0.4, minOf(open, close) - 0.4,
                close, 100.0, openTime + 59_999L, true, 20.0,
            )
        }
        val weakBids = OrderBookSnapshot(10.0, 10.0, 99.9, 100.1, 0.0, 0.2,
            capturedAtMs = 0L, nearBidNotional = 100_000.0, nearAskNotional = 100_000.0)
        val replenishingBids = OrderBookSnapshot(12.0, 9.0, 99.9, 100.1, 0.10, 0.2,
            capturedAtMs = 30_000L, nearBidNotional = 110_000.0, nearAskNotional = 90_000.0)

        val result = EarlyReversalDetector.evaluate(
            data, List(8) { 1.0 }, List(8) { 100.0 }, 102.0,
            orderBook = replenishingBids,
            orderBookHistory = listOf(weakBids, replenishingBids),
            nowMs = 10L * 60L * 1000L,
        )

        org.junit.Assert.assertTrue(result.detail.contains("PANIC SELL ABSORPTION"))
        assertEquals("EARLY UP TREND", result.label)
    }

    @Test
    fun waitsWhenRecentTrendCannotReachTargetBeforeMarketCloses() {
        val data = candles(up = false)
        val histogram = List(8) { it.toDouble() }
        val ema = List(8) { 100.0 }

        val result = EarlyReversalDetector.evaluate(data, histogram, ema, 80.0, nowMs = 10L * 60L * 1000L)

        assertEquals("WAIT • DOWN TREND, TARGET TOO FAR", result.label)
        assertEquals("ABOVE TARGET", result.targetPosition)
    }
}
