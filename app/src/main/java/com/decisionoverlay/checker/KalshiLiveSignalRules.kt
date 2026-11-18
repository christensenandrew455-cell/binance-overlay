package com.decisionoverlay.checker


/**
 * Research path screening against the BTC chart's custom signed percentage.
 * No live Kalshi market data is available or requested.
 * Historical contract-based outcomes are NOT transferable probabilities
 * for these approximate chart levels. Notifications are research watch alerts.
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
        val name: String get() = path.joinToString(" > ") { BtcChartProxyMath.signedPathLevel(it) }
        val side: String get() = if (buyYes) "UP" else "DOWN"
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

    data class Signal(val rule: Rule, val left: Int, val signedChartLevel: Double)

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
     * Match a completed chart minute to the six previously researched paths.
     * Each input quote is the chart's signed 50-90% strength proxy, NOT a
     * Kalshi bid/ask or an executable price. No odds/edge claims are made.
     *
     * Only the first completion in 8-6 minutes is eligible. Overlapping
     * paths are ranked by sample-weighted newer historical net as a
     * heuristic, never as a valid predicted return for chart prices.
     */
    fun matchingProxySignals(
        history: List<KalshiPatternMath.Quote>
    ): List<Signal> {
        val candles = history.filter { it.valid() }
            .sortedByDescending { it.left }.distinctBy { it.left }
        if (candles.size < 2) return emptyList()
        val newest = candles.last()
        val signedLevel = BtcChartProxyMath.signedFromProxy(newest.strength(1))
        return rules.mapNotNull { rule ->
            val completion = firstCompletion(rule.path, candles)
                ?: return@mapNotNull null
            if (completion.left != newest.left ||
                completion.left !in rule.earliestLeft..rule.latestLeft) null
            else Signal(rule, completion.left, signedLevel)
        }.sortedByDescending {
            it.rule.newestN.toDouble() * it.rule.newestNetCents
        }
    }
}
