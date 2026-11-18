package com.decisionoverlay.checker

import kotlin.math.abs

/**
 * Signed values in the live BTC chart's CUSTOM distance-to-target scale.
 * A positive level is above target; a negative level is below target.
 * +/-60..90 is an index, NOT actual BTC returns or Kalshi contract odds.
 */
object BtcChartProxyMath {
    data class Sample(val left: Int, val signedLevel: Double)

    fun strength(price: Double, target: Double, acmPct: Double,
        minutesLeft: Double): Double {
        if (!price.isFinite() || !target.isFinite() || target <= 0.0 ||
            !acmPct.isFinite() || acmPct <= 0.0 ||
            !minutesLeft.isFinite() || minutesLeft <= 0.0) return Double.NaN
        val gapPct = abs((price - target) / target) * 100.0
        return (50.0 + 40.0 * gapPct / (acmPct * minutesLeft))
            .coerceIn(50.0, 90.0)
    }

    fun signedStrength(price: Double, target: Double, acmPct: Double,
        minutesLeft: Double): Double {
        val magnitude = strength(price, target, acmPct, minutesLeft)
        if (!magnitude.isFinite()) return Double.NaN
        return when {
            price > target -> magnitude
            price < target -> -magnitude
            else -> 0.0
        }
    }

    /**
     * Old report thresholds were Kalshi YES quote levels. These are now
     * translated to signed chart thresholds only as an approximation:
     * 20 => -80, 40 => -60, 50 => target, 60 => +60.
     * Historical win rates do NOT apply automatically to these thresholds.
     */
    fun signedFromProxy(oldLevel: Double): Double = when {
        !oldLevel.isFinite() -> Double.NaN
        oldLevel > 50.0 -> oldLevel
        oldLevel < 50.0 -> oldLevel - 100.0
        else -> 0.0
    }

    fun signedPathLevel(oldLevel: Int): String {
        val level = signedFromProxy(oldLevel.toDouble()).toInt()
        return when {
            level > 0 -> "+${level}%"
            level < 0 -> "${level}%"
            else -> "TARGET"
        }
    }

    /**
     * One SIGNED observation for each fully closed 1-minute chart candle.
     * Recompute its ACM60 using only candles completed at that time, so
     * no future data leaks into earlier pattern stages.
     */
    fun completedMinuteSamples(candles: Map<Long, BtcAcmMath.Candle>,
        target: Double, marketStart: Long, completedOpen: Long
    ): List<Sample> {
        val minute = BtcAcmMath.MINUTE_MS
        val close = marketStart + 15L * minute
        val samples = ArrayList<Sample>()
        if (completedOpen < marketStart) return samples
        for (t in marketStart..completedOpen step minute) {
            val c = candles[t] ?: continue
            val minutesLeft = (close - (t + minute)).toDouble() / minute
            if (minutesLeft < 1.0 || minutesLeft >= 15.0) continue
            val acm = BtcAcmMath.averageRange(candles, t, 60) ?: continue
            val signed = signedStrength(c.close, target, acm, minutesLeft)
            if (signed.isFinite()) {
                samples.add(Sample(minutesLeft.toInt(), signed))
            }
        }
        return samples
    }
}
