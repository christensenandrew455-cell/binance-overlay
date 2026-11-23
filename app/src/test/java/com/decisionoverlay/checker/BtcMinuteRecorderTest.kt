package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcMinuteRecorderTest {
    @Test fun recordsAllMinutesAndSameMarketLagsIncludingTies() {
        val step = BtcAcmMath.MINUTE_MS
        val candles = (-60..14).associate { i ->
            val price = if (i == 14) 100.0 else 101.0
            i * step to BtcAcmMath.Candle(i * step, 101.0, 102.0, 98.0, price)
        }
        val rows = BtcMinuteRecorder.rows(0, candles)!!.trimEnd().lines().map {
            BtcMinuteRecorder.header.split(",").zip(it.split(",")).toMap()
        }
        assertEquals(15, rows.size)
        val first = rows.first()
        assertEquals("", first["chart_move_2m_points"])
        val last = rows.last()
        assertEquals("true", last["is_settlement"])
        assertEquals("0.0", last["signed_chart_level"])
        val opening = BtcChartProxyMath.signedStrength(101.0, 100.0, 400.0 / 101.0, 15.0)
        assertEquals(-opening, last.getValue("chart_move_15m_points").toDouble(), 1e-8)
        for (lag in 1..14) {
            val previous = rows[14 - lag].getValue("signed_chart_level").toDouble()
            assertEquals(-previous, last.getValue("chart_move_${lag}m_points").toDouble(), 1e-8)
        }
        for (n in listOf(15, 30, 60))
            assertEquals(-100.0 / 101.0, last.getValue("btc_return_${n}m_pct").toDouble(), 1e-8)
        assertNull(BtcMinuteRecorder.rows(0, candles - (-30L * step)))
    }
}
