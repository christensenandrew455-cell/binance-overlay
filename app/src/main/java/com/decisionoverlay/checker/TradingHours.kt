package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId

/** Session is 10:00 AM to 11:00 PM in New York, including daylight saving time.
 *  A 15-minute market is eligible only if it STARTS from 10:00 through 22:45;
 *  the last permitted market settles at 23:00.
 */
object TradingHours {
    private val newYork = ZoneId.of("America/New_York")
    fun allowedMarketStart(startMillis: Long): Boolean {
        val local = Instant.ofEpochMilli(startMillis).atZone(newYork).toLocalTime()
        return local.hour >= 10 && local.hour < 23
    }

    fun currentQuarterStart(nowMillis: Long): Long =
        nowMillis / 900_000L * 900_000L
}
