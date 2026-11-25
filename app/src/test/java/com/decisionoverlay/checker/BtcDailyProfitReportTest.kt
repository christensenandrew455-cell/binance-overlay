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

    @Test fun dollarMathAndDailyOutcomes() {
        val markets = listOf(market(1, 0, true), market(1, 1, true),
            market(1, 2, false), market(2, 0, false), market(3, 0, true, false),
            market(5, 0, true), market(5, 1, true), market(5, 2, true),
            market(5, 3, false), market(5, 4, false))
        val r = BtcDailyProfitReport.analyze(markets)
        assertEquals(listOf(2000L, -6000L, 0L, 0L, 0L), r.days.map { it.net })
        assertEquals(listOf("PROFIT", "LOSS", "NO TRADES", "MISSING", "BREAK EVEN"),
            r.days.map { it.outcome })
        assertEquals(-4000L, r.net)
        assertEquals(10, r.totalMarkets)
        assertEquals(4, r.observed.size)
        assertTrue(r.report().contains("Break even with trades: 1"))
        assertTrue(r.csv().contains("20.00,PROFIT"))
    }

    @Test fun all52MarketsFullDayAndFeesAndDeduplication() {
        val markets = (0..51).map { market(1, it, true, it == 0) }
        val r = BtcDailyProfitReport.analyze(markets + markets.first(),
            BtcDailyProfitReport.Config(60, 100, 200))
        assertEquals(52, r.totalMarkets)
        assertEquals(1, r.full.size)
        assertEquals(1, r.wins)
        assertEquals(3800L, r.net)
        assertEquals(200L, r.days.single().fees)
    }

    @Test fun multipleMatchingWindowsOnlyCountOneTrade() {
        val original = market(1, 0, true)
        val more = original.copy(minutes = original.minutes.map {
            if (it.minute == 5) it.copy(signedChartLevel = -55.0)
            else if (it.minute == 6) it.copy(signedChartLevel = -65.0) else it
        })
        val r = BtcDailyProfitReport.analyze(listOf(more))
        assertEquals(1, r.wins)
        assertEquals(4000L, r.net)
    }
}
