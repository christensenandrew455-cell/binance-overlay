package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

/**
 * Research model for the BTC 15-minute funnel.
 *
 * The outer 0.05% per minute is a USER-DEFINED "95%" REFERENCE, not a
 * verified probability, especially during the untested first half.
 * Inner lines are selected from OLDER Binance.US markets and checked on
 * NEWER held-out data; missing/failed validations never become alerts.
 */
object BtcFunnelLevels {
    val goals = listOf(60, 70, 80)
    const val CEILING_PER_MINUTE_PCT = 0.05
    const val REQUIRED_PER_PERIOD = 100
    const val SAMPLE_STEP_PCT = 0.01 // 0.01 percentage-point resolution inside the funnel

    data class Level(
        val goal: Int, val gapPct: Double,
        val training: HoldProbabilityEngine.Rate,
        val validation: HoldProbabilityEngine.Rate,
        val validated: Boolean
    ) {
        val observedPct: Double get() = validation.probability * 100.0
        val conservativePct: Double get() =
            min(training.lower95(), validation.lower95()) * 100.0
    }

    fun ceilingPct(minutesLeft: Int): Double {
        require(minutesLeft in 1..15)
        return CEILING_PER_MINUTE_PCT * minutesLeft
    }

    fun priceAt(target: Double, gapPct: Double, side: Int): Double {
        require(target.isFinite() && target > 0.0 &&
            gapPct.isFinite() && gapPct >= 0.0 && side in listOf(1, -1))
        return target * (1.0 + side * gapPct / 100.0)
    }

    fun percentageGap(spot: Double, target: Double): Double =
        if (spot.isFinite() && target.isFinite() && target > 0.0)
            abs((spot - target) / target) * 100.0 else Double.NaN

    fun withinCeiling(gapPct: Double, minutesLeft: Int): Boolean =
        gapPct.isFinite() && gapPct >= 0.0 &&
            gapPct <= ceilingPct(minutesLeft) + 1e-9

    /**
     * Candidate bands are inside, never beyond, the user's ceiling.
     * Accumulated cases cover [candidateGap, outerCeiling], not the
     * larger gaps outside the prohibited trading region.
     *
     * Training alone chooses the lowest qualifying gap. Validation is
     * checked separately and may reject the line; no interpolation.
     */
    fun findLevel(
        history: HoldProbabilityEngine.Historical,
        minutesLeft: Int, side: Int, goal: Int
    ): Level? {
        if (minutesLeft !in 1..15 || side !in listOf(1, -1) ||
            goal !in goals) return null
        val lastBand = (floor((ceilingPct(minutesLeft) - 1e-9) /
            SAMPLE_STEP_PCT).toInt()).coerceIn(0, 149)
        var train = HoldProbabilityEngine.Rate()
        var test = HoldProbabilityEngine.Rate()
        var found: Level? = null
        for (band in lastBand downTo 1) { {
            val a = history.rate(HoldProbabilityEngine.Key(minutesLeft, band, side), false)
            val b = history.rate(HoldProbabilityEngine.Key(minutesLeft, band, side), true)
            train = HoldProbabilityEngine.Rate(train.count + a.count,
                train.successes + a.successes)
            test = HoldProbabilityEngine.Rate(test.count + b.count,
                test.successes + b.successes)
            if (train.count >= REQUIRED_PER_PERIOD &&
                train.probability >= goal / 100.0) {
                found = Level(goal, band * SAMPLE_STEP_PCT,
                    train.copy(), test.copy(), test.count >= REQUIRED_PER_PERIOD &&
                        test.probability >= goal / 100.0)
            }
        }
        return found
    }

    fun comparison(level: Level, ask: Double?): String {
        if (!level.validated) return "NOT VALIDATED"
        if (ask == null || !ask.isFinite() || ask <= 0.0 || ask >= 1.0)
            return "NO KALSHI ASK"
        val fee = HoldProbabilityEngine.estimatedFee(ask)
        val quotedPct = (ask + fee) * 100.0
        return when {
            quotedPct < level.conservativePct -> "BELOW CONSERVATIVE HISTORICAL BOUND"
            ask * 100.0 <= level.observedPct ->
                "AT/BELOW RAW HISTORY, BUT NOT SUPPORTED AFTER FEES/UNCERTAINTY"
            else -> "KALSHI PRICE TOO HIGH FOR THIS HISTORICAL LEVEL"
        }
    }

    fun lineText(level: Level?, target: Double, side: Int): String =
        if (level == null) "UNESTABLISHED"
        else String.format(Locale.US, "$%.2f (%.3f%%) | held-out %.1f%%, n=%d | %s",
            priceAt(target, level.gapPct, side), level.gapPct,
            level.observedPct, level.validation.count,
            if (level.validated) "VALIDATED IN SAMPLE" else "NOT VALIDATED")
}
