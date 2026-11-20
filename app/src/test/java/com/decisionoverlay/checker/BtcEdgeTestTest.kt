package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcEdgeTestTest {
    private val minute = 60_000L

    private fun candle(i: Int, price: Double = 100.0 + i, volume: Double = 10.0) =
        BtcAcmMath.Candle(i * minute, price - 0.25, price + 0.5,
            price - 0.5, price, volume)

    @Test fun candleFramesUseOnlyFullyClosedBarsAndNeverCurrentPartialInterval() {
        val tape = (0..45).associate { i -> i * minute to candle(i) }.toMutableMap()
        val initial = BtcEdgeTest.completedBars(tape, 37L * minute, 5, 2)
        assertNotNull(initial)
        assertEquals(25L * minute, initial!![0].at)
        assertEquals(30L * minute, initial[1].at)
        assertEquals(134.0, initial[1].close, 0.000001)
        assertEquals(50.0, initial[1].volume, 0.000001)

        // The ongoing 35..40 five-minute candle must NOT affect indicators.
        tape[35L * minute] = candle(35, 100_000.0, 9999.0)
        assertEquals(initial, BtcEdgeTest.completedBars(tape, 39L * minute, 5, 2))
        val next = BtcEdgeTest.completedBars(tape, 40L * minute, 5, 2)
        assertEquals(35L * minute, next!!.last().at)
        assertTrue(next.last().high >= 100_000.0)
    }

    @Test fun allSupportedIndicatorsAvailableWithCompleteHistory() {
        val rising = (0 until 22).map { candle(it, 100.0 + it * 0.7, 20.0 + it) }
        val states = BtcEdgeTest.indicators(rising)
        assertEquals(17, BtcEdgeTest.INDICATORS.size)
        assertEquals(BtcEdgeTest.INDICATORS.toSet(), states.keys)
        assertEquals(1, states["ROC10"])
        assertEquals(1, states["RSI14"])
        assertEquals(1, states["Aroon14"])
        assertEquals(1, states["VWAP20"])
        assertTrue(states.values.all { it in -1..1 })
    }

    @Test fun historicalMarketSamplesAllTimeframesAtSameFourCheckpoints() {
        val start = 345L * minute
        val tape = (0..370).associate { i -> i * minute to candle(i, 100 + i / 20.0) }
        val levels = List(14) { if (it % 2 == 0) 65.0 else -75.0 }
        val market = BtcEdgeTest.market(start, 100.0, true, tape, levels)
        assertNotNull(market)
        assertEquals(14, market!!.rows.size)
        assertTrue(market.rows.map { it.left }.containsAll(listOf(11, 8, 5, 2)))
        assertTrue(market.rows.any { row -> row.signals.any { it.timeframe == 1 } })
        assertTrue(market.rows.any { row -> row.signals.any { it.timeframe == 5 } })
        assertTrue(market.rows.any { row -> row.signals.any { it.timeframe == 15 } })
        assertEquals(60, BtcEdgeTest.chartBand(63.0))
        assertEquals(-70, BtcEdgeTest.chartBand(-75.0))
        assertEquals(50, BtcEdgeTest.chartBand(50.0))
    }

    @Test fun discoversAndScoresIndicatorDisagreementAgainstSameCaseChart() {
        val positives = (0 until 500).map { i ->
            BtcEdgeTest.Market(i.toLong(), true,
                listOf(BtcEdgeTest.Row(50, 11, listOf(BtcEdgeTest.Signal(1, "RSI14", 1)))))
        }
        val negatives = (500 until 700).map { i ->
            BtcEdgeTest.Market(i.toLong(), false,
                listOf(BtcEdgeTest.Row(50, 11, listOf(BtcEdgeTest.Signal(1, "RSI14", -1)))))
        }
        val holdout = (700 until 1000).map { i ->
            val down = i < 900
            BtcEdgeTest.Market(i.toLong(), !down,
                listOf(BtcEdgeTest.Row(50, 11, listOf(
                    BtcEdgeTest.Signal(1, "RSI14", if (down) -1 else 1)))))
        }
        val report = BtcEdgeTest.report(positives + negatives + holdout)
        assertTrue(report.contains("CHART ONLY: 100/300 correct"))
        assertTrue(report.contains("RSI14 / 1m / 11 min left / negative -> predict DOWN"))
        assertTrue(report.contains("indicator 200/200 (100.0%), SAME-CASE chart 0/200 (0.0%), lift +100.0 pp"))
        assertTrue(report.contains("Disagreed with chart 200 times; indicator correct on 200/200"))
    }

    @Test fun holdoutOutcomesCannotRefitTrainingPrediction() {
        val train = (0 until 700).map { i ->
            BtcEdgeTest.Market(i.toLong(), true,
                listOf(BtcEdgeTest.Row(70, 8, listOf(BtcEdgeTest.Signal(5, "MACD5/13/4", 1)))))
        }
        val test = (700 until 1000).map { i ->
            BtcEdgeTest.Market(i.toLong(), false,
                listOf(BtcEdgeTest.Row(70, 8, listOf(BtcEdgeTest.Signal(5, "MACD5/13/4", 1)))))
        }
        val report = BtcEdgeTest.report(train + test)
        assertTrue(report.contains("MACD5/13/4 / 5m / 8 min left / positive -> predict UP"))
        assertTrue(report.contains("indicator 0/300 (0.0%)"))
        assertFalse(report.contains("positive -> predict DOWN"))
    }
}
