package com.decisionoverlay.checker

import kotlin.math.abs

/**
 * Recreates the chart's custom distance-to-target 50–90% scale for any
 * completed minute. It is NOT a Kalshi market probability or a contract quote.
 *
 * The chart draws the same 50–90% scale on both sides of the target.
 * To screen historical YES-style paths we express below-target values as
 * 100 - chartStrength (10–50) and above-target as chartStrength (50–90).
 */
object BtcChartProxyMath {
    fun strength(price: Double, target: Double, acmPct: Double,
        minutesLeft: Double): Double {
        if (!price.isFinite() || !target.isFinite() || target <= 0.0 ||
            !acmPct.isFinite() || acmPct <= 0.0 ||
            !minutesLeft.isFinite() || minutesLeft <= 0.0) return Double.NaN
        val gapPct = abs((price - target) / target) * 100.0
        return (50.0 + 40.0 * gapPct / (acmPct * minutesLeft))
            .coerceIn(50.0, 90.0)
    }

    fun yesProxy(price: Double, target: Double, acmPct: Double,
        minutesLeft: Double): Double {
        val strength = strength(price, target, acmPct, minutesLeft)
        if (!strength.isFinite()) return Double.NaN
        return if (price >= target) strength else 100.0 - strength
    }

    /**
     * Recompute the percent displayed by the chart at each finished minute.
     * Use only its then-available 60 completed candles, with no future data.
     * The latest candle is t and its observation occurs at t+60 seconds.
     */
    fun completedMinuteQuotes(candles: Map<Long, BtcAcmMath.Candle>,
        target: Double, marketStart: Long, completedOpen: Long
    ): List<KalshiPatternMath.Quote> {
        val minute = BtcAcmMath.MINUTE_MS
        val close = marketStart + 15L * minute
        val quotes = ArrayList<KalshiPatternMath.Quote>()
        if (completedOpen < marketStart) return quotes
        for (t in marketStart..completedOpen step minute) {
            val c = candles[t] ?: continue
            val secondsLeft = close - (t + minute)
            if (secondsLeft < minute || secondsLeft >= 15L * minute) continue
            val acm = BtcAcmMath.averageRange(candles, t, 60) ?: continue
            val yes = yesProxy(c.close, target, acm, secondsLeft.toDouble() / minute)
            if (!yes.isFinite()) continue
            quotes.add(KalshiPatternMath.Quote(
                (secondsLeft / minute).toInt(), yes / 100.0, yes / 100.0))
        }
        return quotes
    }
}
