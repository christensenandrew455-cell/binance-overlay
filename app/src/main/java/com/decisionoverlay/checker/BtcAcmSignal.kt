package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Experimental rule from the user's 100-market study:
 * ACM60 × minutes-left × K0.25, YES when above the Kalshi strike,
 * NO when below, and a 60–69c executable ask.
 * 21/27 is descriptive IN-SAMPLE, never a calibrated 77.8% prediction.
 */
object BtcAcmSignal {
    const val MULTIPLIER = 0.25
    const val LOW_ASK = 0.60
    const val HIGH_ASK = 0.69
    const val HISTORICAL_WINS = 21
    const val HISTORICAL_ENTRIES = 27
    const val MIN_EDGE_PP = 5.0
    const val MIN_REMAINING_SECONDS = 60.0 // do not alert inside the final minute
    const val MAX_REMAINING_SECONDS = 8.0 * 60.0

    val sampleWinRate get() = HISTORICAL_WINS.toDouble() / HISTORICAL_ENTRIES

    data class Decision(
        val direction: String,
        val spot: Double,
        val target: Double,
        val gapPct: Double,
        val requiredPct: Double,
        val minutesBucket: Int,
        val secondsLeft: Double,
        val ask: Double?,
        val contracts: Int,
        val totalCost: Double,
        val fee: Double,
        val breakEven: Double,
        val rawEdgePercentagePoints: Double,
        val estimatedProfitIfWin: Double,
        val reason: String,
        val buyEligible: Boolean
    )

    fun evaluate(
        spot: Double, target: Double, acmPct: Double,
        secondsRemaining: Double, yesAsk: Double?, noAsk: Double?,
        eligibleMarket: Boolean = true
    ): Decision {
        val validPrice = spot.isFinite() && target.isFinite() &&
            spot > 0.0 && target > 0.0
        val side = when {
            !validPrice -> 0
            spot > target -> 1
            spot < target -> -1
            else -> 0
        }
        val direction = if (side == 1) "YES" else if (side == -1) "NO" else "WAIT"
        val minute = if (secondsRemaining.isFinite() && secondsRemaining > 0)
            ceil(secondsRemaining / 60.0).toInt().coerceIn(1, 15) else 0
        val threshold = if (minute > 0 && acmPct.isFinite() && acmPct > 0)
            acmPct * minute * MULTIPLIER else Double.NaN
        val gap = if (validPrice) abs((spot - target) / target * 100.0)
            else Double.NaN
        val ask = when(side) { 1 -> yesAsk; -1 -> noAsk; else -> null }
        val position = if (ask != null) BtcAcmProfitMath.tenDollarPosition(ask, true)
            else null
        val fee = position?.fee ?: Double.NaN
        val cost = position?.cost ?: Double.NaN
        val breakEven = if (position != null && position.contracts > 0)
            cost / position.contracts else Double.NaN
        val edge = if (breakEven.isFinite()) (sampleWinRate - breakEven) * 100.0
            else Double.NaN
        val good = when {
            !eligibleMarket -> "Outside approved 10 AM–11 PM BTC market"
            side == 0 -> "BTC at target or invalid spot/target"
            !secondsRemaining.isFinite() || secondsRemaining > MAX_REMAINING_SECONDS ->
                "Wait for second half of 15-minute market"
            secondsRemaining <= MIN_REMAINING_SECONDS ->
                "Final minute blocked to avoid late fills"
            !threshold.isFinite() -> "Need 60 complete 1-minute candles"
            gap + 1e-10 < threshold -> "BTC has not reached ACM60 × time × 0.25"
            ask == null || !ask.isFinite() -> "Kalshi ASK unavailable — no BUY"
            ask < LOW_ASK || ask >= HIGH_ASK + 0.01 - 1e-9 ->
                "Kalshi contract outside the tested 60–69¢ buy range"
            position == null -> "Cannot size an order within $10"
            edge + 1e-9 < MIN_EDGE_PP ->
                "Kalshi fee-adjusted breakeven too high for sample estimate"
            else -> "BUY $direction — experimental historical price/ACM match"
        }
        return Decision(direction, spot, target, gap, threshold, minute,
            secondsRemaining, ask, position?.contracts ?: 0, cost, fee,
            breakEven, edge, position?.pnl ?: Double.NaN,
            good, good.startsWith("BUY "))
    }
}
