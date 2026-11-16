package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalSignalReplayTest {
    @Test fun yesWinsTiesButNoLosesTies() {
        assertTrue(HistoricalSignalReplay.proxyWon(1, 100.0, 100.0))
        assertEquals(false, HistoricalSignalReplay.proxyWon(-1, 100.0, 100.0))
        assertTrue(HistoricalSignalReplay.proxyWon(1, 101.0, 100.0))
        assertTrue(HistoricalSignalReplay.proxyWon(-1, 99.0, 100.0))
        assertEquals(false, HistoricalSignalReplay.proxyWon(1, 99.0, 100.0))
        assertEquals(false, HistoricalSignalReplay.proxyWon(-1, 101.0, 100.0))
    }

    @Test fun replayWindowOnlyLooksAtFiveAndFourMinutesLeft() {
        assertEquals(10..11, HistoricalSignalReplay.entryMinuteOffsets)
        assertEquals(9, HistoricalSignalReplay.supportedCoins.distinct().size)
        val minute = (0 until 180).map { i ->
            val p = 100.0 + i * .1
            SignalEngine.Bar(i * 60_000L, i * 60_000L + 59_999L,
                p - .1, p + .15, p - .20, p, 100.0)
        }
        val five = (0 until 180).map { i ->
            val p = 100.0 + i * .1
            SignalEngine.Bar(i * 300_000L, i * 300_000L + 299_999L,
                p - .1, p + .15, p - .20, p, 100.0)
        }
        val spot = minute.last().close
        val target = spot + 1.0
        assertTrue(SignalEngine.replayCandle(minute, five, spot, target, 6.0).reason.startsWith("Outside"))
        assertTrue(SignalEngine.replayCandle(minute, five, spot, target, 3.0).reason.startsWith("Outside"))
        assertFalse(SignalEngine.replayCandle(minute, five, spot, target, 5.0).reason.startsWith("Outside"))
        assertFalse(SignalEngine.replayCandle(minute, five, spot, target, 4.0).reason.startsWith("Outside"))
    }

    @Test fun impossibleTargetSidesNeverTriggerCandleSignal() {
        val minute = (0 until 180).map { i ->
            val p = 100.0 + i * .1
            SignalEngine.Bar(i * 60_000L, i * 60_000L + 59_999L,
                p - .1, p + .15, p - .20, p, 100.0)
        }
        val five = (0 until 180).map { i ->
            val p = 100.0 + i * .1
            SignalEngine.Bar(i * 300_000L, i * 300_000L + 299_999L,
                p - .1, p + .15, p - .20, p, 100.0)
        }
        val spot = minute.last().close
        // Both sides are unable to enter a contrary-to-target trade here.
        val upAlreadyAhead = SignalEngine.replayCandle(minute, five, spot, spot - 1, 8.0)
        assertEquals(0, upAlreadyAhead.direction)
    }
}
