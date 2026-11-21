package com.decisionoverlay.checker

import kotlin.math.abs

/**
 * Four frozen research-only BTC pattern alerts. Exact-time rates come from
 * "EIGHT SELECTED BTC PATTERNS: 1,000 MARKET TEST", Nov 20 2026.
 * The chart level is a signed custom INDEX, NOT a Kalshi quote/probability.
 * No live contract execution or profitability is implied.
 */
object KalshiLiveSignalRules {
    const val MIN_HIT_RATE_PERCENT = 20.0
    const val MIN_EDGE_POINTS = 15.0 // Minimum 15 percentage POINTS above the 60 chart INDEX
    const val MIN_DISCOVERY_WINDOW_HITS = 20

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

    // The 20% occurrence filter remains separate from the time-specific edge.
    val rules: List<Rule> = listOf(
        Rule(-50, -60, 2, false, 343, 273, 134, 82.1),
        Rule(-50, -60, 1, false, 373, 294, 144, 79.9),
        Rule(+50, +60, 1, true, 307, 226, 107, 79.4),
        Rule(+50, +60, 2, true, 282, 204, 98, 78.6)
    ).filter { it.hitRatePercent >= MIN_HIT_RATE_PERCENT }

    data class WindowKey(
        val fromBand: Int, val toBand: Int, val gapMinutes: Int,
        val startLeft: Int, val finishLeft: Int
    )

    data class Window(
        val key: WindowKey, val hits: Int, val wins: Int,
        val olderHits: Int, val olderWins: Int,
        val newerHits: Int, val newerWins: Int
    ) {
        val winRatePercent: Double get() = wins * 100.0 / hits
        val olderWinPercent: Double get() = olderWins * 100.0 / olderHits
        val newerWinPercent: Double get() =
            if (newerHits == 0) Double.NaN else newerWins * 100.0 / newerHits
        // The selected side agrees with the signed destination band:
        // -60 corresponds to DOWN index 60, and +60 to UP index 60.
        val chartSideIndex: Double get() = abs(key.toBand).toDouble()
        val edgePoints: Double get() = winRatePercent - chartSideIndex
        // INCLUSIVE: 75% - 60 = 15 percentage points DOES qualify.
        // Require 20+ discovery-market matches to exclude tiny-sample 100% rows.
        val eligible: Boolean get() =
            olderHits >= MIN_DISCOVERY_WINDOW_HITS &&
                edgePoints >= MIN_EDGE_POINTS
    }

    // 11 columns: from,to,gap,startLeft,finishLeft,hits,wins,
    // olderHits,olderWins,newerHits,newerWins.
    // These are FROZEN reported outcomes, not continuously estimated odds.
    private val observedWindows: Map<WindowKey, Window> = """
            -50,-60,1,14,13,30,26,18,17,12,9
            -50,-60,1,13,12,48,43,30,28,18,15
            -50,-60,1,12,11,40,30,28,22,12,8
            -50,-60,1,11,10,41,29,27,18,14,11
            -50,-60,1,10,9,56,49,34,30,22,19
            -50,-60,1,9,8,45,34,24,18,21,16
            -50,-60,1,8,7,42,32,23,16,19,16
            -50,-60,1,7,6,33,20,19,12,14,8
            -50,-60,1,6,5,33,26,20,14,13,12
            -50,-60,1,5,4,30,23,18,12,12,11
            -50,-60,1,4,3,23,19,12,9,11,10
            -50,-60,1,3,2,11,8,10,7,1,1
            -50,-60,1,2,1,7,4,3,2,4,2
            -50,-60,2,14,12,58,53,37,35,21,18
            -50,-60,2,13,11,60,51,40,35,20,16
            -50,-60,2,12,10,60,44,40,28,20,16
            -50,-60,2,11,9,66,52,41,31,25,21
            -50,-60,2,10,8,71,60,41,34,30,26
            -50,-60,2,9,7,52,43,28,22,24,21
            -50,-60,2,8,6,46,32,27,17,19,15
            -50,-60,2,7,5,43,33,24,16,19,17
            -50,-60,2,6,4,38,29,22,15,16,14
            -50,-60,2,5,3,24,18,16,12,8,6
            -50,-60,2,4,2,14,12,9,7,5,5
            -50,-60,2,3,1,4,2,3,1,1,1
            50,60,1,14,13,31,23,21,15,10,8
            50,60,1,13,12,38,28,25,19,13,9
            50,60,1,12,11,34,22,26,17,8,5
            50,60,1,11,10,37,32,23,19,14,13
            50,60,1,10,9,46,35,31,25,15,10
            50,60,1,9,8,34,24,19,10,15,14
            50,60,1,8,7,36,30,25,21,11,9
            50,60,1,7,6,43,30,25,14,18,16
            50,60,1,6,5,29,18,18,10,11,8
            50,60,1,5,4,14,12,6,5,8,7
            50,60,1,4,3,23,16,15,9,8,7
            50,60,1,3,2,5,2,5,2,0,0
            50,60,1,2,1,1,1,1,1,0,0
            50,60,2,14,12,44,32,26,19,18,13
            50,60,2,13,11,46,28,33,22,13,6
            50,60,2,12,10,51,42,36,29,15,13
            50,60,2,11,9,61,48,42,33,19,15
            50,60,2,10,8,51,38,32,24,19,14
            50,60,2,9,7,42,32,26,19,16,13
            50,60,2,8,6,45,38,28,22,17,16
            50,60,2,7,5,39,24,27,15,12,9
            50,60,2,6,4,18,14,11,8,7,6
            50,60,2,5,3,24,17,14,9,10,8
            50,60,2,4,2,10,6,7,3,3,3
            50,60,2,3,1,3,3,3,3,0,0
        """.trimIndent().lineSequence()
        .filter { it.isNotBlank() }
        .map { line ->
            val v = line.split(',').map { it.trim().toInt() }
            require(v.size == 11 && v[5] > 0 && v[5] == v[7] + v[9] &&
                v[6] == v[8] + v[10] && v[3] - v[4] == v[2]) {
                "Bad frozen exact-minute sample: " + line
            }
            val key = WindowKey(v[0], v[1], v[2], v[3], v[4])
            key to Window(key, v[5], v[6], v[7], v[8], v[9], v[10])
        }.toMap()

    fun windowFor(rule: Rule, startLeft: Int, finishLeft: Int): Window? =
        observedWindows[WindowKey(rule.fromBand, rule.toBand,
            rule.gapMinutes, startLeft, finishLeft)]

    fun eligibleWindowCount(rule: Rule): Int =
        observedWindows.values.count { it.key.fromBand == rule.fromBand &&
            it.key.toBand == rule.toBand && it.key.gapMinutes == rule.gapMinutes &&
            it.eligible }

    data class Signal(
        val rule: Rule,
        val startLeft: Int,
        val left: Int,
        val signedChartLevel: Double,
        val window: Window
    )

    /**
     * Only exact signed 10-point bins on COMPLETED candles count.
     * Compare historical WIN RATE for that exact start/finish time, not the
     * overall path win rate. Later qualifying completions are allowed when
     * an earlier match was ineligible, but only one signal per rule/market.
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

            val window = windowFor(rule, startLeft, latest.left)
                ?.takeIf { it.eligible } ?: return@mapNotNull null

            val earlierQualified = samples.any { prior ->
                if (prior.left <= latest.left) false else {
                    val earlierStart = prior.left + rule.gapMinutes
                    BtcExhaustivePatternReport.band(prior.signedLevel) == rule.toBand &&
                        BtcExhaustivePatternReport.band(
                            byLeft[earlierStart]?.signedLevel ?: Double.NaN
                        ) == rule.fromBand &&
                        windowFor(rule, earlierStart, prior.left)?.eligible == true
                }
            }
            if (earlierQualified) null
            else Signal(rule, startLeft, latest.left, latest.signedLevel, window)
        }.sortedWith(compareByDescending<Signal> { it.window.winRatePercent }
            .thenByDescending { it.window.olderHits })
    }
}
