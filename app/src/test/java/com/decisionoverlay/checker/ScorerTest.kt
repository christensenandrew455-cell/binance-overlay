package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScorerTest {
    @Test fun strictnessProfilesIncreaseConfirmationRequirements() {
        val loose = StrictnessProfile.fromLevel(0)
        val balanced = StrictnessProfile.fromLevel(1)
        val strict = StrictnessProfile.fromLevel(2)
        val veryStrict = StrictnessProfile.fromLevel(3)
        val ultra = StrictnessProfile.fromLevel(4)

        assertEquals(1, loose.requiredConfirmations)
        assertEquals(2, balanced.requiredConfirmations)
        assertEquals(3, strict.requiredConfirmations)
        assertEquals(4, veryStrict.requiredConfirmations)
        assertEquals(5, ultra.requiredConfirmations)
        assertTrue(loose.volumeExpansionRatio < balanced.volumeExpansionRatio)
        assertTrue(balanced.volumeExpansionRatio < strict.volumeExpansionRatio)
        assertTrue(strict.volumeExpansionRatio < veryStrict.volumeExpansionRatio)
        assertTrue(veryStrict.volumeExpansionRatio < ultra.volumeExpansionRatio)
        assertEquals(5, ultra.scoreThreshold)
    }

    @Test fun exactTargetPositionReturnsDnt() {
        val data = candles(45, 1)
        val result = Scorer.evaluateTarget(data, candles(45, 1, 300000L), candles(45, 1, 900000L),
            feeds(), data.last().close, data.last().close)
        assertEquals(Verdict.DNT, result.candidate)
        assertTrue(result.reason.contains("binary target"))
    }

    @Test fun targetDistanceDoesNotCreateAnEntryByItself() {
        val data = candles(45, -1)
        val result = Scorer.evaluateTarget(data, candles(45, -1, 300000L), candles(45, -1, 900000L),
            feeds(), data.last().close + 10000.0, data.last().close, strictnessLevel = 0)
        assertEquals(Verdict.DNT, result.candidate)
        assertEquals(0.0, result.buffer, 0.0)
    }

    @Test fun missingFeedForcesDnt() {
        val data = candles(45, 1)
        val bad = feeds().toMutableList()
        bad[1] = FeedStatus(ConnectionState.NOT_WORKING, "offline", null, "HTTP error")
        val result = Scorer.evaluateTarget(data, candles(45, 1, 300000L), candles(45, 1, 900000L),
            bad, data.last().close + 100.0, data.last().close)
        assertEquals(Verdict.DNT, result.candidate)
        assertFalse(result.problems.isEmpty())
    }

    @Test fun targetSideIsBinaryAndOnlyPermitsReversalTowardTarget() {
        val data = candles(45, -1)
        val belowTarget = Scorer.evaluateTarget(data, candles(45, -1, 300000L), candles(45, -1, 900000L),
            feeds(), data.last().close + 1.0, data.last().close, strictnessLevel = 0)
        assertEquals(Verdict.DNT, belowTarget.candidate)
        assertTrue(belowTarget.reason.contains("below target"))
    }

    private fun feeds() = listOf("1m", "5m", "15m").map {
        FeedStatus(ConnectionState.CONNECTED, "test", 1L, null)
    }

    private fun candles(count: Int, direction: Int, interval: Long = 60000L): List<Candle> {
        var price = 100.0
        return (0 until count).map { index ->
            val next = price + direction * 0.2
            val candle = Candle(
                openTime = index * interval,
                open = price,
                high = maxOf(price, next) + 0.05,
                low = minOf(price, next) - 0.05,
                close = next,
                volume = 10.0,
                closeTime = (index + 1) * interval - 1,
                closed = true,
            )
            price = next
            candle
        }
    }
}
