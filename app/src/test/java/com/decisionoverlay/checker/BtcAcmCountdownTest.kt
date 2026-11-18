package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcAcmCountdownTest {
    @Test fun boundariesContinuouslyConvergeToTargetWithSecondsNotSteps() {
        val marketTarget = 65_000.0
        val acm = 0.02
        val at8Min = BtcAcmCountdown.boundaries(marketTarget, acm, 480_000L)
        val at7m30s = BtcAcmCountdown.boundaries(marketTarget, acm, 450_000L)
        val at4Min = BtcAcmCountdown.boundaries(marketTarget, acm, 240_000L)
        val atEnd = BtcAcmCountdown.boundaries(marketTarget, acm, 0L)
        assertTrue(at8Min.first > at7m30s.first)
        assertTrue(at7m30s.first > at4Min.first)
        assertTrue(at4Min.first > atEnd.first)
        assertTrue(at8Min.second < at7m30s.second)
        assertTrue(at7m30s.second < at4Min.second)
        assertEquals(marketTarget, atEnd.first, 1e-8)
        assertEquals(marketTarget, atEnd.second, 1e-8)
        assertEquals(65013.0, at4Min.first, 1e-8)
        assertEquals(64987.0, at4Min.second, 1e-8)
    }

    @Test fun seriesRecomputesPastCurveOnlyWithCompletedCandles() {
        val minute = BtcAcmMath.MINUTE_MS
        val end = 100L * minute
        val now = end - 4L*minute - 30_000L // 4:30 left
        val bars = (0..94).associate { i ->
            val time = i.toLong() * minute
            val c = BtcAcmMath.Candle(time, 100.0,100.05,99.95,100.0)
            time to c
        }
        val points = BtcAcmCountdown.series(bars,100.0,end,now,0.1,100.25)
        assertFalse(points.isEmpty())
        assertTrue(points.last().projected)
        assertEquals(end,points.last().at)
        assertEquals(100.0,points.last().upper,1e-9)
        assertEquals(100.0,points.last().lower,1e-9)
        val current = points.last { !it.projected }
        assertEquals(now,current.at)
        assertEquals(100.25,current.btcPrice!!,1e-9)
        assertEquals(100.1125,current.upper,1e-9)
        assertTrue(points.filter{it.projected}.all { it.btcPrice == null })
        assertTrue(points.zipWithNext().all { (a,b) -> a.at <= b.at })
    }

    @Test fun continuousThresholdDoesNotJumpAtMinuteBoundary() {
        val before = BtcAcmSignal.evaluate(100.025,100.0,0.02,
            241.0,0.65,0.65)
        val after = BtcAcmSignal.evaluate(100.025,100.0,0.02,
            239.0,0.65,0.65)
        assertTrue(before.requiredPct > after.requiredPct)
        assertTrue(before.requiredPct-after.requiredPct < 0.0001)
    }
}
