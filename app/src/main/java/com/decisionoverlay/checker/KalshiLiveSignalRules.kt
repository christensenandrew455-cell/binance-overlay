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

    /**
     * Match actual signed chart levels in chronological minute order.
     * Old historical threshold 20 becomes -80 on this custom scale.
     * Every stage must occur in a later COMPLETED minute.
     */
    fun firstCompletion(path: List<Int>,
        sortedOldestFirst: List<BtcChartProxyMath.Sample>
    ): BtcChartProxyMath.Sample? {
        if (path.size < 2) return null
        var from = 0
        var last: BtcChartProxyMath.Sample? = null
        for (stage in path.indices) {
            val current = BtcChartProxyMath.signedFromProxy(path[stage].toDouble())
            val rising = if (stage == 0) path[1] > path[0]
                else path[stage] > path[stage - 1]
            val index = (from until sortedOldestFirst.size).firstOrNull { i ->
                val value = sortedOldestFirst[i].signedLevel
                when {
                    stage == 0 && rising -> value <= current + 1e-7
                    stage == 0 -> value >= current - 1e-7
                    rising -> value >= current - 1e-7
                    else -> value <= current + 1e-7
                }
            } ?: return null
            last = sortedOldestFirst[index]
            from = index + 1
        }
        return last
    }

    /**
     * Watch ONLY when a historical path shape first completes
     * on a fully closed BTC chart minute, 8-6 minutes before settlement.
     * No contract prices or any claimed real-world probability edge.
     */
    fun matchingSignedSignals(history: List<BtcChartProxyMath.Sample>): List<Signal> {
        val samples = history.filter {
            it.left in 1..14 && it.signedLevel.isFinite() &&
                (it.signedLevel == 0.0 ||
                 kotlin.math.abs(it.signedLevel) in 50.0..90.0)
        }.sortedByDescending { it.left }.distinctBy { it.left }
        if (samples.size < 2) return emptyList()
        val newest = samples.last()
        return rules.mapNotNull { rule ->
            val completion = firstCompletion(rule.path, samples)
                ?: return@mapNotNull null
            if (completion.left != newest.left ||
                completion.left !in rule.earliestLeft..rule.latestLeft) null
            else Signal(rule, completion.left, newest.signedLevel)
        }.sortedByDescending {
            it.rule.newestN.toDouble() * it.rule.newestNetCents
        }
    }
}
