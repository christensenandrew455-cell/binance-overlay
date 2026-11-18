package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Fixed user-requested 50-90 scale. It is a rule, not a measured likelihood.
 * A separately marked normal-volatility projection is used ONLY as a synthetic
 * Kalshi-like price. It is NOT a real or historical Kalshi quote.
 */
object BtcRangeMath {
    const val MINUTE_MS = 60_000L
    const val MARKET_MS = 15L * MINUTE_MS
    const val TARGET_MARKETS = 100
    const val MIN_LEVEL = 51
    const val MAX_LEVEL = 90
    const val LOW_ASK = 60.0
    const val HIGH_ASK = 79.0

    data class Snapshot(val percentage: Double, val modeledAskPct: Double,
        val feePct: Double, val remaining: Int) {
        val qualifies: Boolean
            get() = modeledAskPct in LOW_ASK..HIGH_ASK &&
                percentage + 1e-9 >= modeledAskPct + feePct
    }

    fun rulePercentage(gapPct: Double, averageOneMinuteRangePct: Double,
        minutesLeft: Int): Double? {
        if (!gapPct.isFinite() || gapPct < 0.0 ||
            !averageOneMinuteRangePct.isFinite() || averageOneMinuteRangePct <= 0.0 ||
            minutesLeft !in 1..14) return null
        val ceiling = averageOneMinuteRangePct * minutesLeft
        if (ceiling <= 0.0) return null
        return 50.0 + 40.0 * (gapPct / ceiling).coerceIn(0.0, 1.0)
    }

    /** First crossing only: all newly attained whole-number percentages. */
    fun firstCrossed(previousMax: Int, nowPct: Double): IntRange {
        val reached = if (nowPct.isFinite())
            floor(nowPct + 1e-9).toInt().coerceIn(50, MAX_LEVEL) else 50
        return (previousMax.coerceIn(50, MAX_LEVEL) + 1)..reached
    }

    fun oneMinuteLogVolatility(candles: Map<Long, BtcAcmMath.Candle>,
        lastCompletedOpen: Long): Double? {
        var sum = 0.0
        var squares = 0.0
        for (i in 0 until 60) {
            val candle = candles[lastCompletedOpen - i * MINUTE_MS] ?: return null
            if (!candle.valid()) return null
            val change = ln(candle.close / candle.open)
            sum += change
            squares += change * change
        }
        val mean = sum / 60.0
        val variance = (squares / 60.0 - mean * mean).coerceAtLeast(0.0)
        val sigma = sqrt(variance)
        return sigma.takeIf { it > 1e-9 && it.isFinite() }
    }

    // Abramowitz-Stegun normal-CDF approximation, for a no-drift synthetic quote.
    private fun normalCdf(z: Double): Double {
        val positive = abs(z)
        val t = 1.0 / (1.0 + 0.2316419 * positive)
        val polynomial = t * (0.319381530 + t * (-0.356563782 +
            t * (1.781477937 + t * (-1.821255978 + t * 1.330274429))))
        val cdf = 1.0 - exp(-0.5 * positive * positive) /
            sqrt(2.0 * Math.PI) * polynomial
        return if (z < 0.0) 1.0 - cdf else cdf
    }

    fun syntheticAskPct(price: Double, target: Double,
        oneMinuteSigma: Double, minutesLeft: Int): Double? {
        if (price <= 0.0 || target <= 0.0 || oneMinuteSigma <= 0.0 ||
            !price.isFinite() || !target.isFinite() ||
            !oneMinuteSigma.isFinite() || minutesLeft !in 1..14) return null
        val z = abs(ln(price / target)) / (oneMinuteSigma * sqrt(minutesLeft.toDouble()))
        // Whole-cent synthetic prices, not historical executable orderbook asks.
        return (normalCdf(z) * 100.0).toInt().coerceIn(1, 99).toDouble()
    }

    /** Illustration of Kalshi general taker fee for one contract, rounded to cents.
     * The actual rate can differ by market and order type.
     */
    fun illustrativeFeePct(askPct: Double): Double {
        if (!askPct.isFinite() || askPct !in 1.0..99.0) return Double.NaN
        val p = askPct / 100.0
        return ceil(7.0 * p * (1.0 - p) - 1e-10)
    }

    fun snapshot(candles: Map<Long, BtcAcmMath.Candle>,
        observationCandleOpen: Long, spot: Double, target: Double,
        minutesLeft: Int): Snapshot? {
        if (spot <= 0.0 || target <= 0.0 || minutesLeft !in 1..14) return null
        val range = BtcAcmMath.averageRange(candles, observationCandleOpen, 60)
            ?: return null
        val gapPct = abs((spot - target) / target * 100.0)
        val percentage = rulePercentage(gapPct, range, minutesLeft) ?: return null
        val sigma = oneMinuteLogVolatility(candles, observationCandleOpen)
            ?: return null
        val ask = syntheticAskPct(spot, target, sigma, minutesLeft) ?: return null
        return Snapshot(percentage, ask, illustrativeFeePct(ask), minutesLeft)
    }
}
