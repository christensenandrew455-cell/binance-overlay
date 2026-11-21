package com.decisionoverlay.checker

/**
 * Exactly four research alerts selected from the 1,000-market closed-candle
 * Binance.US BTC backtest. Percentages below are observed historical
 * frequencies and settlement outcomes, not Kalshi contract prices or profits.
 */
object KalshiLiveSignalRules {
    const val MIN_HIT_RATE_PERCENT = 20.0

    data class Rule(
        val fromBand: Int,
        val toBand: Int,
        val gapMinutes: Int,
        val buyUp: Boolean,
        val hits: Int,
        val wins: Int,
        val newerHits: Int,
        val newerWinPercent: Double
    ) {
        val side: String get() = if (buyUp) "UP" else "DOWN"
        val name: String get() = label(fromBand) + " -> " + label(toBand) +
            " in " + gapMinutes + " min"
        val hitRatePercent: Double get() = hits * 100.0 / 1000.0
        val winRatePercent: Double get() = wins * 100.0 / hits
    }

    private fun label(band: Int) = (if (band > 0) "+" else "") + band.toString()

    // Locked overall rates from the supplied 8-pattern 1,000-market report.
    // The 20% rule concerns occurrence across ALL markets, not occurrences
    // within one particular 1- or 2-minute clock-time pair.
    val rules: List<Rule> = listOf(
        Rule(-50, -60, 2, false, 343, 273, 134, 82.1),
        Rule(-50, -60, 1, false, 373, 294, 144, 79.9),
        Rule(+50, +60, 1, true, 307, 226, 107, 79.4),
        Rule(+50, +60, 2, true, 282, 204, 98, 78.6)
    ).filter { it.hitRatePercent >= MIN_HIT_RATE_PERCENT }

    data class Signal(
        val rule: Rule,
        val startLeft: Int,
        val left: Int,
        val signedChartLevel: Double
    )

    /**
     * Uses the very same 10-point SIGNED bins as the exhaustive backtest,
     * not a threshold crossing and not the visible unsigned 50 line.
     * Candles must have CLOSED exactly gapMinutes apart. Never peek ahead.
     */
    fun matchingSignedSignals(history: List<BtcChartProxyMath.Sample>): List<Signal> {
        val samples = history.filter {
            it.left in 1..14 && it.signedLevel.isFinite()
        }.sortedByDescending { it.left }.distinctBy { it.left }
        val latest = samples.lastOrNull() ?: return emptyList()
        val byLeft = samples.associateBy { it.left }
        return rules.mapNotNull { rule ->
            val startLeft = latest.left + rule.gapMinutes
            val first = byLeft[startLeft] ?: return@mapNotNull null
            if (BtcExhaustivePatternReport.band(first.signedLevel) != rule.fromBand ||
                BtcExhaustivePatternReport.band(latest.signedLevel) != rule.toBand
            ) return@mapNotNull null

            // At most the first completion of a given rule in this market.
            val alreadyCompleted = samples.any { prior ->
                prior.left > latest.left &&
                    BtcExhaustivePatternReport.band(prior.signedLevel) == rule.toBand &&
                    BtcExhaustivePatternReport.band(
                        byLeft[prior.left + rule.gapMinutes]?.signedLevel ?: Double.NaN
                    ) == rule.fromBand
            }
            if (alreadyCompleted) null
            else Signal(rule, startLeft, latest.left, latest.signedLevel)
        }.sortedWith(compareByDescending<Signal> { it.rule.newerWinPercent }
            .thenByDescending { it.rule.hits })
    }
}
