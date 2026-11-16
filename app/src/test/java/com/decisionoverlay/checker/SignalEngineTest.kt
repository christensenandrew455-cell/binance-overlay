package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SignalEngineTest {
    @Test fun directionMustCrossKalshiTarget() {
        assertTrue(SignalEngine.targetAllows(1, 99.0, 100.0))
        assertTrue(SignalEngine.targetAllows(-1, 101.0, 100.0))
        assertEquals(false, SignalEngine.targetAllows(1, 101.0, 100.0))
        assertEquals(false, SignalEngine.targetAllows(-1, 99.0, 100.0))
        assertEquals(false, SignalEngine.targetAllows(1, 100.0, 100.0))
        assertEquals(false, SignalEngine.targetAllows(-1, 100.0, 100.0))
    }

    @Test fun travelBudgetReflectsVolatilityAndTime() {
        val littleTime = SignalEngine.travelBudget(1.0, 3.0, 0.0)
        val moreTime = SignalEngine.travelBudget(1.0, 8.0, 0.0)
        assertTrue(moreTime > littleTime)
        assertTrue(SignalEngine.travelBudget(1.0, 8.0, 0.3) > moreTime)
    }

    private fun sample(direction: Int, targetGap: Double): SignalEngine.Snapshot {
        val now = 1_800_000_000_000L
        fun bars(interval: Long, step: Double): List<SignalEngine.Bar> =
            (0 until 180).map { i ->
                val time = now - (180 - i) * interval
                val close = 100.0 + direction * (i * step)
                val open = close - direction * step
                SignalEngine.Bar(time, time + interval - 1,
                    open, maxOf(open, close) + step * 0.4,
                    minOf(open, close) - step * 0.4, close,
                    if (i == 179) 300.0 else 100.0)
            }
        val one = bars(60_000L, 0.1)
        val five = bars(300_000L, 0.12)
        val spot = one.last().close + direction * 0.005
        val bids = listOf(SignalEngine.Level(spot * 0.9995, if (direction > 0) 100.0 else 10.0))
        val asks = listOf(SignalEngine.Level(spot * 1.0005, if (direction < 0) 100.0 else 10.0))
        val trades = (0 until 30).map { i ->
            SignalEngine.Trade(spot, 2.0, now - 20_000 + i * 100L,
                buyerWasMaker = direction < 0)
        }
        return SignalEngine.Snapshot("BTC", "TEST", spot + targetGap,
            now + 7 * 60_000L, now, spot, one, five, bids, asks, trades)
    }

    @Test fun buyUpOnlyIfBelowTargetAndEnoughPower() {
        val valid = SignalEngine.evaluate(sample(1, 0.2))
        assertEquals(valid.report, "BUY UP", valid.verdict)
        assertTrue(valid.buyerPower!! > 60)
        val wrongSide = SignalEngine.evaluate(sample(1, -0.2))
        assertEquals("WAIT", wrongSide.verdict)
    }

    @Test fun buyDownOnlyIfAboveTargetAndEnoughPower() {
        val valid = SignalEngine.evaluate(sample(-1, -0.2))
        assertEquals(valid.report, "BUY DOWN", valid.verdict)
        assertTrue(valid.sellerPower!! > 60)
        val wrongSide = SignalEngine.evaluate(sample(-1, 0.2))
        assertEquals("WAIT", wrongSide.verdict)
    }

    @Test fun neverSignalsInsideLastThreeMinutes() {
        val s = sample(1, 0.2)
        val late = s.copy(marketClosesAt = s.observedAt + 2 * 60_000L)
        val result = SignalEngine.evaluate(late)
        assertEquals("WAIT", result.verdict)
    }
}
