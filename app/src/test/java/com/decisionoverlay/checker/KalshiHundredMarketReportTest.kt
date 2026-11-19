package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class KalshiHundredMarketReportTest {
    private val eastern = ZoneId.of("America/New_York")
    private fun closeAt(year: Int, month: Int, day: Int, h: Int, m: Int): Long =
        ZonedDateTime.of(year, month, day, h, m, 0, 0, eastern)
            .toEpochSecond() + 900L

    @Test fun bucketsAreTenPercentRangesNotExactTargetMatches() {
        assertEquals(0, KalshiHundredMarketReport.band(0.0))
        assertEquals(0, KalshiHundredMarketReport.band(9.9))
        assertEquals(60, KalshiHundredMarketReport.band(60.0))
        assertEquals(60, KalshiHundredMarketReport.band(69.9))
        assertEquals(70, KalshiHundredMarketReport.band(70.0))
        assertEquals(90, KalshiHundredMarketReport.band(100.0))
    }

    @Test fun sessionRespectsEasternTenAmAndElevenPmBoundaries() {
        assertFalse(KalshiHundredMarketReport.isAllowedSession(
            closeAt(2026, 11, 19, 9, 45)))
        assertTrue(KalshiHundredMarketReport.isAllowedSession(
            closeAt(2026, 11, 19, 10, 0)))
        assertTrue(KalshiHundredMarketReport.isAllowedSession(
            closeAt(2026, 11, 19, 22, 45)))
        assertFalse(KalshiHundredMarketReport.isAllowedSession(
            closeAt(2026, 11, 19, 23, 0)))
    }

    @Test fun eachMarketHasFifteenChronologicalColumnsAndFinalOutcome() {
        val market = KalshiHundredMarketReport.Market(
            "KXBTC15M-TEST", closeAt(2026, 11, 19, 10, 0),
            yesWon = false, minutes = listOf(
                KalshiHundredMarketReport.MinutePrice(1, 70.0, false),
                KalshiHundredMarketReport.MinutePrice(3, 55.5, true),
                KalshiHundredMarketReport.MinutePrice(15, 60.0, false)
            )
        )
        val report = KalshiHundredMarketReport.report(listOf(market))
        val header = "market_number,ticker,market_start_et,market_end_et,outcome,observed_minutes," +
            (1..15).joinToString(",") { "M" + it.toString().padStart(2, '0') }
        assertTrue(report.contains(header))
        assertTrue(report.contains("complete 15-minute rows=0/1"))
        assertTrue(report.contains("observed minute cells=3/15"))
        assertTrue(report.contains("close-only fallback cells=1"))
        assertTrue(report.contains(",DOWN,3,70,NA,50,NA"))
        assertTrue(report.contains(",DOWN,3,70.0,NA,55.5,NA"))
        assertTrue(report.contains(",60\n"))
        assertTrue(report.contains(",60.0\n"))
        assertTrue(report.contains("NOT a true time-weighted"))
        assertFalse(report.contains("ROI="))
    }

    @Test fun oneHundredMarketReportHasHundredRowsInEachMatrix() {
        val markets = (0 until 100).map { i ->
            val startDay = if (i < 50) 17 else 18
            val indexInDay = i % 50
            val startMinute = 10 * 60 + 15 * indexInDay
            val close = closeAt(2026, 11, startDay,
                startMinute / 60, startMinute % 60)
            KalshiHundredMarketReport.Market(
                "KXBTC15M-$i", close, i % 2 == 0,
                (1..15).map { minute ->
                    KalshiHundredMarketReport.MinutePrice(
                        minute, 50.0 + minute, false)
                }
            )
        }
        val report = KalshiHundredMarketReport.report(markets)
        assertTrue(report.contains("Settled distinct markets: 100/100"))
        assertTrue(report.contains("complete 15-minute rows=100/100"))
        assertTrue(report.contains("settled UP=50 DOWN=50"))
        assertTrue(report.contains("observed minute cells=1500/1500"))
        assertFalse(report.contains("INCOMPLETE DATASET"))
        assertTrue(report.contains("=== TEN-BAND YES PERCENT MATRIX (PRIMARY) ==="))
        assertTrue(report.contains("=== RAW AVERAGE YES PERCENT PER MINUTE (1 DECIMAL) ==="))
        val first = report.substringAfter(
            "=== TEN-BAND YES PERCENT MATRIX (PRIMARY) ===")
            .substringBefore("=== RAW AVERAGE")
        val second = report.substringAfter(
            "=== RAW AVERAGE YES PERCENT PER MINUTE (1 DECIMAL) ===")
            .substringBefore("=== END")
        assertEquals(100, first.lines().count { it.startsWith("KXBTC15M-").not() &&
            it.matches(Regex("^[0-9]+,.*")) })
        assertEquals(100, second.lines().count {
            it.matches(Regex("^[0-9]+,.*"))
        })
    }
}
