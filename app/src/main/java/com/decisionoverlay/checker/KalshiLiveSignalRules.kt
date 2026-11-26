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

    /** A qualifying start only, not a prediction or a confirmed buy. */
    data class EarlySignal(val rule: Rule, val window: Window)

    /**
     * Match the latest COMPLETED signed 50-band sample to the same frozen
     * eligible finish windows used by matchingSignedSignals. Never inspect
     * future candles or treat an unfinished candle as a confirmed start.
     */
    fun matchingEarlyWindows(
        history: List<BtcChartProxyMath.Sample>, elapsedSinceCloseMs: Long,
        neutralBuyUp: Boolean? = null
    ): List<EarlySignal> {
        if (elapsedSinceCloseMs < 0L) return emptyList()
        val samples = history.filter {
            it.left in 1..14 && it.signedLevel.isFinite()
        }.sortedByDescending { it.left }.distinctBy { it.left }
        val latest = samples.lastOrNull() ?: return emptyList()
        val byLeft = samples.associateBy { it.left }
        val startBand = BtcExhaustivePatternReport.band(latest.signedLevel)
        return rules.mapNotNull { rule ->
            val neutralStart = latest.signedLevel == 0.0 && neutralBuyUp == rule.buyUp
            if ((!neutralStart && startBand != rule.fromBand) ||
                elapsedSinceCloseMs >= rule.gapMinutes * 60_000L
            ) return@mapNotNull null
            val window = windowFor(rule, latest.left, latest.left - rule.gapMinutes)
                ?.takeIf { it.eligible } ?: return@mapNotNull null
            // Mirror the existing per-rule first eligible completion guard.
            val earlierQualified = samples.any { prior ->
                val earlierStart = prior.left + rule.gapMinutes
                BtcExhaustivePatternReport.band(prior.signedLevel) == rule.toBand &&
                    BtcExhaustivePatternReport.band(
                        byLeft[earlierStart]?.signedLevel ?: Double.NaN
                    ) == rule.fromBand &&
                    windowFor(rule, earlierStart, prior.left)?.eligible == true
            }
            if (earlierQualified) null else EarlySignal(rule, window)
        }
    }

    /** Timestamped chart prices, not contract prices. */
    data class CenterPoint(val at: Long, val price: Double)

    /**
     * An exact center reading or an observed crossing brackets a touch.
     * Use the earlier observation timestamp for crossings conservatively.
     * Ignore the synthetic market-opening target point and gaps over 30s.
     * A flat stay exactly on target does not refresh the initial touch.
     */
    fun latestCenterTouch(points: List<CenterPoint>, target: Double,
        marketStart: Long, observedAt: Long): Long? {
        if (!target.isFinite() || target <= 0.0) return null
        val ordered = points.filter {
            it.at > marketStart && it.at <= observedAt && it.price.isFinite()
        }.sortedBy { it.at }.distinctBy { it.at }
        var previous: CenterPoint? = null
        var touch: Long? = null
        for (point in ordered) {
            val prior = previous
            if (point.price == target && prior?.price != target) {
                touch = point.at
            } else if (prior != null && point.at - prior.at in 1L..30_000L &&
                ((prior.price < target && point.price > target) ||
                 (prior.price > target && point.price < target))) {
                touch = prior.at
            }
            previous = point
        }
        return touch
    }

    data class ProgressWarning(val startLeft: Int, val windows: List<EarlySignal>)

    /**
     * Require an actual observed center touch/crossing, then movement to
     * +55 or -55 before both the original exact-minute finish deadline
     * and the rule's elapsed-time limit from that center touch.
     * Neutral completed starts can arm either side, for warnings ONLY.
     * Existing signed completed-candle BUY rules are never changed.
     */
    fun matchingProgressWarnings(
        history: List<BtcChartProxyMath.Sample>,
        elapsedSinceLatestCloseMs: Long,
        liveSignedLevel: Double,
        centerTouchAgeMs: Long
    ): List<ProgressWarning> {
        if (elapsedSinceLatestCloseMs < 0L || centerTouchAgeMs !in 0L..119_999L ||
            !liveSignedLevel.isFinite() || abs(liveSignedLevel) < 55.0 ||
            abs(liveSignedLevel) >= 60.0) return emptyList()
        val samples = history.filter {
            it.left in 1..14 && it.signedLevel.isFinite()
        }.sortedByDescending { it.left }.distinctBy { it.left }
        val latest = samples.lastOrNull() ?: return emptyList()
        return samples.indices.reversed().mapNotNull { i ->
            val start = samples[i]
            val elapsed = (start.left - latest.left) * 60_000L +
                elapsedSinceLatestCloseMs
            if (elapsed <= 0L || elapsed >= 120_000L ||
                centerTouchAgeMs > elapsed + 60_000L ||
                (start.signedLevel != 0.0 &&
                    (liveSignedLevel > 0.0) != (start.signedLevel > 0.0)))
                return@mapNotNull null
            val windows = matchingEarlyWindows(samples.take(i + 1), elapsed,
                neutralBuyUp = liveSignedLevel > 0.0).filter {
                centerTouchAgeMs < it.rule.gapMinutes * 60_000L &&
                    it.rule.buyUp == (liveSignedLevel > 0.0)
            }
            if (windows.isEmpty()) null else ProgressWarning(start.left, windows)
        }
    }

    // Frozen from BTC-reversal-20261125-223617.csv: combined UP/DOWN first BUY,
    // all 1,000 markets, rounded win rate >=80%. In-sample research, not
    // a validated probability for the new early trigger.
    private val filteredFinishMinutes = mapOf(
        10 to setOf(4,7,9,11,12),
        11 to setOf(4,5,7,9,12),
        12 to setOf(4,6,8,9,11,12),
        13 to setOf(4,5,6,7,11,12),
        14 to setOf(5,7,8,11,12),
        15 to setOf(4,10,12),
        16 to setOf(5,6,9),
        17 to setOf(5,7,8,9,10,12),
        18 to setOf(4,5,7,9),
        19 to setOf(7,11),
        20 to setOf(6,7,8,10,11,12),
        21 to setOf(4,5,6,7,8,9,11,12),
        22 to setOf(4,8,9,10,12)
    )
    fun filteredTime(hour: Int, finishLeft: Int): Boolean =
        filteredFinishMinutes[hour]?.contains(finishLeft) == true

    data class LiveReading(val at: Long, val level: Double, val price: Double)
    data class NearBuy(val rule: Rule, val window: Window, val finishLeft: Int,
        val elapsedMs: Long, val projectedMsTo60: Long)

    /**
     * Only fresh observed readings, no synthetic minute-close or center points.
     * Same side 50..52 to 55..59.99 in <=30s, outward price movement, and
     * projected 60 before the original eligible destination candle closes.
     * No claim that the price will actually reach 60 or settle in this direction.
     */
    fun matchingFilteredNearBuy(history: List<BtcChartProxyMath.Sample>,
        readings: List<LiveReading>, marketStart: Long, hour: Int): NearBuy? {
        val latest=readings.lastOrNull() ?: return null
        val previous=readings.getOrNull(readings.lastIndex-1) ?: return null
        val magnitude=abs(latest.level)
        if (!magnitude.isFinite() || magnitude<55.0 || magnitude>=60.0 ||
            !latest.price.isFinite() || latest.price<=0 ||
            abs(previous.level)>=55.0 || latest.at-previous.at !in 1L..10_000L ||
            latest.at !in (marketStart+60_000L) until (marketStart+14*60_000L)) return null
        val side=if(latest.level>0) 1 else -1
        if (previous.level*side<=0) return null
        val prior=readings.asReversed().drop(1).firstOrNull {
            it.at>=marketStart && latest.at-it.at in 1L..30_000L &&
                it.level*side in 50.0..52.0
        } ?: return null
        val path=readings.filter { it.at in prior.at..latest.at }
        if(path.any { !it.level.isFinite() || !it.price.isFinite() || it.level*side<=0 } ||
            path.zipWithNext().any { (a,b) -> b.at-a.at !in 1L..10_000L ||
                abs(b.level)<abs(a.level) || (b.price-a.price)*side<0 } ||
            (latest.price-prior.price)*side<=0) return null
        val elapsed=latest.at-prior.at
        val velocity=(magnitude-abs(prior.level))/elapsed
        if (velocity<=0 || !velocity.isFinite()) return null
        val projected=kotlin.math.ceil((60.0-magnitude)/velocity).toLong()
        val finishAt=marketStart+((latest.at-marketStart)/60_000L+1)*60_000L
        val finishLeft=((marketStart+15*60_000L-finishAt)/60_000L).toInt()
        if(!filteredTime(hour,finishLeft) || projected>finishAt-latest.at) return null
        val completed=history.filter { it.left>finishLeft && it.left in 1..14 }.associateBy { it.left }
        return rules.mapNotNull { rule ->
            if(rule.buyUp!=(side>0)) return@mapNotNull null
            val startLeft=finishLeft+rule.gapMinutes
            if(BtcExhaustivePatternReport.band(completed[startLeft]?.signedLevel ?: Double.NaN)!=rule.fromBand)
                return@mapNotNull null
            val window=windowFor(rule,startLeft,finishLeft)?.takeIf { it.eligible }
                ?: return@mapNotNull null
            val alreadyMatched=completed.values.any { earlier ->
                BtcExhaustivePatternReport.band(earlier.signedLevel)==rule.toBand &&
                    BtcExhaustivePatternReport.band(completed[earlier.left+rule.gapMinutes]?.signedLevel ?: Double.NaN)==rule.fromBand &&
                    windowFor(rule,earlier.left+rule.gapMinutes,earlier.left)?.eligible==true
            }
            if(alreadyMatched) null else NearBuy(rule,window,finishLeft,elapsed,projected)
        }.maxByOrNull { it.window.winRatePercent }
    }

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
