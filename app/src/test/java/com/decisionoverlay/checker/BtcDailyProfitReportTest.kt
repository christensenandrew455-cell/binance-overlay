package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BtcDailyProfitReportTest {
    private val zone = ZoneId.of("America/New_York")
    private fun market(day: Int, quarter: Int, win: Boolean, signal: Boolean = true):
        BinanceTwentySevenPatternReport.Market {
        val start = LocalDate.of(2026, 11, day).atTime(10, 0).atZone(zone)
            .plusMinutes(quarter * 15L).toInstant().toEpochMilli()
        val levels = (1..14).map {
            BinanceTwentySevenPatternReport.Observation(it,
                if (signal && it == 2) -55.0
                else if (signal && it == 3) -65.0 else -90.0)
        }
        return BinanceTwentySevenPatternReport.Market(start, 100.0,
            if (win) 99.0 else 101.0, levels)
    }

    @Test fun fixedStakePayoutAndTwoWins() {
        val c = BtcDailyProfitReport.Config()
        assertEquals(1000L, c.cost)
        assertEquals(1400L, c.payout)
        assertEquals(400L, c.win)
        assertEquals(-1000L, c.loss)
        val r = BtcDailyProfitReport.analyze(listOf(market(1, 0, true), market(1, 1, true)))
        assertEquals(800L, r.net)
        assertEquals(2000L, r.days.single().grossCost)
        assertTrue(r.csv().contains("8.00,PROFIT"))
    }

    @Test fun dollarMathAndDailyOutcomes() {
        val markets = listOf(market(1, 0, true), market(1, 1, true),
            market(1, 2, false), market(2, 0, false), market(3, 0, true, false),
            market(5, 0, true), market(5, 1, true), market(5, 2, true),
            market(5, 3, true), market(5, 4, true), market(5, 5, false),
            market(5, 6, false), market(6, 0, true), market(6, 1, true))
        val r = BtcDailyProfitReport.analyze(markets)
        assertEquals(listOf(-200L, -1000L, 0L, 0L, 0L, 800L), r.days.map { it.net })
        assertEquals(listOf("LOSS", "LOSS", "NO TRADES", "MISSING", "BREAK EVEN", "PROFIT"),
            r.days.map { it.outcome })
        assertEquals(-400L, r.net)
        assertEquals(14, r.totalMarkets)
        assertEquals(5, r.observed.size)
        assertTrue(r.report().contains("Break even with trades: 1"))
        assertTrue(r.csv().contains("8.00,PROFIT"))
    }

    @Test fun all52MarketsFullDayAndDeduplication() {
        val markets = (0..51).map { market(1, it, true, it == 0) }
        val r = BtcDailyProfitReport.analyze(markets + markets.first())
        assertEquals(52, r.totalMarkets)
        assertEquals(1, r.full.size)
        assertEquals(1, r.wins)
        assertEquals(400L, r.net)
    }

    @Test fun multipleMatchingWindowsOnlyCountOneTrade() {
        val original = market(1, 0, true)
        val more = original.copy(minutes = original.minutes.map {
            if (it.minute == 5) it.copy(signedChartLevel = -55.0)
            else if (it.minute == 6) it.copy(signedChartLevel = -65.0) else it
        })
        val r = BtcDailyProfitReport.analyze(listOf(more))
        assertEquals(1, r.wins)
        assertEquals(400L, r.net)
    }
}
