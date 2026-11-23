package com.decisionoverlay.checker

import kotlin.math.abs

/**
 * AVERAGE CANDLE MOVEMENT experiment, not a statistical probability model.
 * All timestamps are 1-minute candle OPEN timestamps.
 * The average uses only already COMPLETED, consecutive candles.
 */
object BtcAcmMath {
    const val MINUTE_MS = 60_000L
    val WINDOWS = listOf(3, 15, 30, 60)
    const val FIRST_ELAPSED = 7
    const val LAST_ELAPSED = 14
    const val TARGET_STAY_PCT = 95.0

    data class Candle(
        val at: Long,
        val open: Double,
        val high: Double,
        val low: Double,
        val close: Double,
        val volume: Double = Double.NaN
    ) {
        val midpoint: Double get() = low + (high - low) / 2.0
        val rangePct: Double get() = (high - low) / open * 100.0
        fun valid(): Boolean = open > 0.0 && high >= low && low > 0.0 &&
            close > 0.0 && listOf(open, high, low, close).all { it.isFinite() }
    }

    data class Result(val side: Int, val stay: Boolean, val tied: Boolean, val touched: Boolean)

    fun averageRange(
        candles: Map<Long, Candle>, lastCompletedOpen: Long, count: Int
    ): Double? {
        if (count !in WINDOWS) return null
        var sum = 0.0
        for (i in 0 until count) {
            val time = lastCompletedOpen - i * MINUTE_MS
            val c = candles[time] ?: return null
            if (!c.valid()) return null
            sum += c.rangePct
        }
        return sum / count
    }

    fun thresholdPct(acmPct: Double, minutesLeft: Int): Double {
        require(acmPct.isFinite() && acmPct >= 0.0 && minutesLeft in 1..15)
        return acmPct * minutesLeft
    }

    fun percentFromTarget(price: Double, target: Double): Double {
        require(price.isFinite() && target.isFinite() && target > 0.0)
        return (price - target) / target * 100.0
    }

    /**
     * Reaches or exceeds ACM * remaining-minutes distance on the
     * observed side. The result is measured later, NEVER used to trigger.
     */
    fun triggered(spot: Double, target: Double, acm: Double, minutesLeft: Int): Boolean {
        if (!spot.isFinite() || !target.isFinite() || target <= 0.0 ||
            !acm.isFinite() || acm <= 0.0 || minutesLeft !in 1..15) return false
        return abs(percentFromTarget(spot, target)) + 1e-10 >= thresholdPct(acm, minutesLeft)
    }

    /**
     * STAY means strictly on the observed side at the final 1m close.
     * Ties are separately counted. TOUCH checks all remaining candle
     * highs/lows, even if the final close stays on the same side.
     */
    fun outcome(
        side: Int, target: Double, finalClose: Double,
        future: List<Candle>
    ): Result {
        require(side == 1 || side == -1)
        val tied = finalClose == target
        val stay = if (side == 1) finalClose > target else finalClose < target
        val touched = future.any {
            if (side == 1) it.low <= target else it.high >= target
        }
        return Result(side, stay, tied, touched)
    }
}
