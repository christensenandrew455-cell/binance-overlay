package com.decisionoverlay.checker

import kotlin.math.sqrt

/**
 * Live screening of paths selected from the user's 1,000-market historical report.
 * The older/newer numbers are exploratory, not calibrated probabilities or proof.
 * Levels always refer to the REAL Kalshi YES bid/ask midpoint, never Binance.
 */
object KalshiLiveSignalRules {
    data class Rule(
        val path: List<Int>,
        val buyYes: Boolean,
        val earliestLeft: Int,
        val latestLeft: Int,
        val olderN: Int,
        val newestN: Int,
        val newestWinRate: Double,
        val olderNetCents: Double,
        val newestNetCents: Double
    ) {
        val name: String get() = path.joinToString(">")
        val side: String get() = if (buyYes) "YES" else "NO"
    }

    // Retained only if BOTH older and newest 30% samples had positive
    // fee-adjusted modeled net, the newest split had >=20 observations and
    // occurrence >=7% of the 300 newest eligible markets.
    // Rules overlap: they must NOT be treated as independent opportunities.
    val rules = listOf(
        Rule(listOf(40, 60), false, 6, 8, 49, 21, .429, 18.8, 11.3),
        Rule(listOf(50, 60), false, 6, 8, 65, 26, .385, 12.3, 6.0),
        Rule(listOf(40, 20), true, 6, 8, 92, 54, .204, 7.9, 3.8),
        Rule(listOf(50, 20), true, 6, 8, 69, 37, .216, 6.2, 4.9),
        Rule(listOf(30, 20), true, 6, 8, 102, 54, .185, 5.6, 2.1),
        Rule(listOf(60, 20), true, 6, 8, 37, 22, .227, 10.5, 5.9)
    )

    data class Signal(
        val rule: Rule, val left: Int, val liveAsk: Double, val fee: Double,
        val estimatedEdge: Double
    )

    /** Mirrors the report's earliest multi-minute threshold sequence exactly. */
    fun firstCompletion(path: List<Int>, sortedOldestFirst: List<KalshiPatternMath.Quote>):
        KalshiPatternMath.Quote? {
        if (path.size < 2) return null
        var from = 0
        var last: KalshiPatternMath.Quote? = null
        for (stage in path.indices) {
            val rising = if (stage == 0) path[1] > path[0]
                else path[stage] > path[stage - 1]
            val level = path[stage].toDouble()
            val index = (from until sortedOldestFirst.size).firstOrNull { i ->
                val midpoint = sortedOldestFirst[i].strength(1)
                if (rising) {
                    if (stage == 0) midpoint <= level + 1e-7
                    else midpoint >= level - 1e-7
                } else {
                    if (stage == 0) midpoint >= level - 1e-7
                    else midpoint <= level + 1e-7
                }
            } ?: return null
            last = sortedOldestFirst[index]
            from = index + 1 // Every stage must happen in a later minute.
        }
        return last
    }

    /**
     * Evaluate only after the latest one-minute candle finishes.
     * A fresh live ask (not a candle ask) is the actual price comparison.
     * Require a 3 percentage-point edge ABOVE the modeled one-contract fee.
     */
    fun matchingSignals(
        history: List<KalshiPatternMath.Quote>,
        liveYesBid: Double,
        liveYesAsk: Double
    ): List<Signal> {
        if (!liveYesBid.isFinite() || !liveYesAsk.isFinite() ||
            liveYesBid < .01 || liveYesAsk > .99 ||
            liveYesBid > liveYesAsk || liveYesAsk - liveYesBid > .08)
            return emptyList()
        val candles = history.filter { it.valid() }
            .sortedByDescending { it.left }.distinctBy { it.left }
        if (candles.size < 2) return emptyList()
        val newest = candles.last()
        val mid = 50 * (liveYesBid + liveYesAsk)
        // The minute candle's threshold still needs to hold in the live quote.
        if (kotlin.math.abs(newest.strength(1) - mid) > 10.0) return emptyList()
        val signals = ArrayList<Signal>()
        for (rule in rules) {
            val completion = firstCompletion(rule.path, candles) ?: continue
            if (completion.left != newest.left ||
                completion.left !in rule.earliestLeft..rule.latestLeft) continue
            val lastUp = rule.path.last() > rule.path[rule.path.lastIndex - 1]
            val target = rule.path.last().toDouble()
            if (lastUp && mid < target || !lastUp && mid > target) continue

            val ask = if (rule.buyYes) liveYesAsk else 1.0 - liveYesBid
            if (ask !in .01.. .99) continue
            val fee = KalshiPatternMath.approximateFee(ask)
            val edge = rule.newestWinRate - ask - fee
            if (edge < .03) continue
            signals.add(Signal(rule, completion.left, ask, fee, edge))
        }
        // Prefer high live margin and penalize noisy small historical samples.
        return signals.sortedByDescending {
            val p = it.rule.newestWinRate
            it.estimatedEdge - .5 * sqrt(p * (1 - p) / it.rule.newestN)
        }
    }
}
