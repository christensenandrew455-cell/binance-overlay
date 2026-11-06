package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ScorerTest {
    @Test
    fun impulseAlignsUpOnEveryTimeframe() {
        val candles = impulse(1)
        val decision = scoreAll(candles, connected())
        assertTrue("5m score ${decision.five.score}", decision.five.score >= 75)
        assertTrue("15m score ${decision.fifteen.score}", decision.fifteen.score >= 30)
        assertTrue("1h score ${decision.hour.score}", decision.hour.score >= 50)
        assertEquals(Verdict.UP, decision.verdict)
        assertEquals(0, decision.five.components.first { it.name == "RSI zone" }.points)
        assertEquals(25, decision.five.components.first { it.name == "MACD expand" }.points)
    }

    @Test
    fun selloffAlignsDown() {
        val decision = scoreAll(impulse(-1), connected())
        assertTrue(decision.five.score <= -75)
        assertTrue(decision.fifteen.score <= -30)
        assertTrue(decision.hour.score <= -50)
        assertEquals(Verdict.DOWN, decision.verdict)
    }

    @Test
    fun chopAndFlatAreDoNotTrade() {
        val chop = scoreAll(oscillation(0.25), connected())
        val flat = scoreAll(oscillation(0.01), connected())
        assertEquals(Verdict.DNT, chop.verdict)
        assertEquals(Verdict.DNT, flat.verdict)
        assertTrue(abs(chop.hour.score) < 50)
        assertTrue(abs(flat.fifteen.score) < 30)
        assertEquals(0, chop.fifteen.components.first { it.name == "RSI momentum" }.points)
        assertEquals(0, chop.fifteen.components.first { it.name == "Candle net" }.points)
    }

    @Test
    fun brokenFeedForcesDoNotTradeEvenWhenScoresAlign() {
        val candles = impulse(1)
        val decision = Scorer.decide(
            Scorer.score5m(candles, broken("HTTP 451 from api.binance.com")),
            Scorer.score15m(candles, connected()),
            Scorer.score1h(candles, connected()),
        )
        assertEquals(Verdict.DNT, decision.verdict)
        assertTrue(decision.reason.contains("Not Working"))
    }

    @Test
    fun neutralHourBlocksALowerTimeframeImpulse() {
        val decision = Scorer.decide(
            Scorer.score5m(impulse(1), connected()),
            Scorer.score15m(impulse(1), connected()),
            Scorer.score1h(oscillation(0.01), connected()),
        )
        assertEquals(Verdict.DNT, decision.verdict)
        assertTrue(decision.reason.contains("1h"))
    }

    private fun scoreAll(candles: List<Candle>, feed: FeedStatus): Decision = Scorer.decide(
        Scorer.score5m(candles, feed),
        Scorer.score15m(candles, feed),
        Scorer.score1h(candles, feed),
    )

    private fun connected() = FeedStatus(ConnectionState.CONNECTED, "test", 0L, null)

    private fun broken(error: String) = FeedStatus(ConnectionState.NOT_WORKING, "Not Working", null, error)

    private fun impulse(sign: Int): List<Candle> {
        val closes = ArrayList<Double>()
        var price = 100.0
        for (index in 0 until 70) {
            val step = if (index % 5 == 4) -0.12 else 0.18
            price += sign * step
            closes += price
        }
        for (index in 0 until 8) {
            price += sign * (if (index % 2 == 0) 0.02 else -0.015)
            closes += price
        }
        for (index in 0 until 8) {
            price += sign * (0.35 + index * 0.08)
            closes += price
        }
        return candlesFromCloses(closes)
    }

    private fun oscillation(amplitude: Double): List<Candle> {
        val closes = (0 until 90).map { index -> 100.0 + if (index % 2 == 0) amplitude else -amplitude }
        return candlesFromCloses(closes)
    }

    private fun candlesFromCloses(closes: List<Double>): List<Candle> = closes.mapIndexed { index, close ->
        val open = if (index == 0) close else closes[index - 1]
        Candle(
            openTime = index * 60_000L,
            open = open,
            high = maxOf(open, close),
            low = minOf(open, close),
            close = close,
            volume = 10.0,
            closeTime = (index + 1) * 60_000L - 1,
            closed = true,
        )
    }
}
