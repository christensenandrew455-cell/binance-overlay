package com.decisionoverlay.checker

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TradingHoursTest {
    private fun utc(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun winterNewYorkSession() {
        // Eastern standard time (UTC-5)
        assertFalse(TradingHours.allowedMarketStart(utc("2026-11-16T14:45:00Z"))) // 09:45
        assertTrue(TradingHours.allowedMarketStart(utc("2026-11-16T15:00:00Z"))) // 10:00
        assertTrue(TradingHours.allowedMarketStart(utc("2026-11-17T03:45:00Z"))) // 22:45
        assertFalse(TradingHours.allowedMarketStart(utc("2026-11-17T04:00:00Z"))) // 23:00
    }

    @Test fun summerNewYorkSessionUsesDst() {
        // Eastern daylight time (UTC-4)
        assertFalse(TradingHours.allowedMarketStart(utc("2026-06-01T13:45:00Z"))) // 09:45
        assertTrue(TradingHours.allowedMarketStart(utc("2026-06-01T14:00:00Z"))) // 10:00
        assertTrue(TradingHours.allowedMarketStart(utc("2026-06-02T02:45:00Z"))) // 22:45
        assertFalse(TradingHours.allowedMarketStart(utc("2026-06-02T03:00:00Z"))) // 23:00
    }

    @Test fun historicalSamplingReplacesExcludedHours() {
        val end = utc("2026-11-16T20:00:00Z")
        val starts = HistoricalSignalReplay.lastEligibleStarts(end, 2400)
        org.junit.Assert.assertEquals(2400, starts.size)
        assertTrue(starts.all { TradingHours.allowedMarketStart(it) })
        assertTrue(starts.zipWithNext().all { (a, b) -> b > a })
        // 13 eligible hours out of 24, so go back farther than 2400 calendar quarters.
        assertTrue(end - starts.first() > 2400L * 15L * 60L * 1000L)
        assertTrue(starts.last() < end)
    }
}
