package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorerTest {
    @Test fun bullishReversalBelowTargetScoresTenAndSignalsUp() {
        val one = candles(45, 1, volumeBoost = true, reversal = true)
        val five = candles(45, 1, volumeBoost = true, interval = 300000L, strongLastThree = true)
        val fifteen = candles(45, 1, interval = 900000L)
        val result = Scorer.evaluateTarget(one, five, fifteen, feeds(), 130.0, one.last().close)
        assertEquals(10, result.score)
        assertEquals(Verdict.UP, result.candidate)
    }

    @Test fun bearishReversalAboveTargetScoresMinusTenAndSignalsDown() {
        val one = candles(45, -1, volumeBoost = true, reversal = true)
        val five = candles(45, -1, volumeBoost = true, interval = 300000L, strongLastThree = true)
        val fifteen = candles(45, -1, interval = 900000L)
        val result = Scorer.evaluateTarget(one, five, fifteen, feeds(), 80.0, one.last().close)
        assertEquals(-6, result.score)
        assertEquals(Verdict.DOWN, result.candidate)
    }

    @Test fun priceInsideTargetBufferStaysDnt() {
        val one = candles(45, 1, volumeBoost = true)
        val five = candles(45, 1, volumeBoost = true, interval = 300000L, strongLastThree = true)
        val fifteen = candles(45, 1, interval = 900000L)
        val price = one.last().close
        val result = Scorer.evaluateTarget(one, five, fifteen, feeds(), price + 0.01, price)
        assertEquals(Verdict.DNT, result.candidate)
        assertEquals(-2, result.targetPoints)
    }

    @Test fun missingFeedForcesDnt() {
        val one = candles(45, 1, volumeBoost = true)
        val five = candles(45, 1, volumeBoost = true, interval = 300000L, strongLastThree = true)
        val fifteen = candles(45, 1, interval = 900000L)
        val bad = feeds().toMutableList()
        bad[1] = FeedStatus(ConnectionState.NOT_WORKING, "offline", null, "HTTP error")
        val result = Scorer.evaluateTarget(one, five, fifteen, bad, 130.0, one.last().close)
        assertEquals(Verdict.DNT, result.candidate)
        assertEquals(false, result.problems.isEmpty())
    }

    @Test fun fiveMinuteConfirmationCannotScoreAgainstTargetSideBias() {
        val one = candles(45, -1, volumeBoost = true)
        val five = candles(45, 1, volumeBoost = true, interval = 300000L, strongLastThree = true)
        val fifteen = candles(45, 1, interval = 900000L)
        val result = Scorer.evaluateTarget(one, five, fifteen, feeds(), 130.0, one.last().close)
        assertEquals(Verdict.DNT, result.candidate)
    }

    private fun feeds() = listOf("1m", "5m", "15m").map {
        FeedStatus(ConnectionState.CONNECTED, "test", 1L, null)
    }

    private fun candles(
        count: Int,
        direction: Int,
        volumeBoost: Boolean = false,
        interval: Long = 60000L,
        strongLastThree: Boolean = false,
        reversal: Boolean = false,
    ): List<Candle> {
        var price = 100.0
        return (0 until count).map { index ->
            val inReversalLeg = reversal && index >= count - 3
            val candleDirection = if (reversal && !inReversalLeg) -direction else direction
            val step = if ((strongLastThree || inReversalLeg) && index >= count - 3) 0.5 else 0.2
            val next = price + candleDirection * step
            val volume = if (volumeBoost && index >= count - 5) 20.0 else 10.0
            val candle = Candle(
                openTime = index * interval,
                open = price,
                high = maxOf(price, next) + 0.05,
                low = minOf(price, next) - 0.05,
                close = next,
                volume = volume,
                closeTime = (index + 1) * interval - 1,
                closed = true,
            )
            price = next
            candle
        }
    }
}