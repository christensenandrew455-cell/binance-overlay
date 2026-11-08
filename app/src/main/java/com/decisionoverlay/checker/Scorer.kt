package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.max

data class TargetEvaluation(
    val score: Int,
    val candidate: Verdict,
    val buffer: Double,
    val reason: String,
    val problems: List<String>,
    val oneMinutePoints: Int = 0,
    val fiveMinutePoints: Int = 0,
    val targetPoints: Int = 0,
)

object Scorer {
    const val MIN_CANDLES = 40
    const val DEFAULT_DNT_THRESHOLD = 3
    const val MIN_DNT_THRESHOLD = 3
    const val MAX_DNT_THRESHOLD = 3

    fun score1m(c: List<Candle>, f: FeedStatus) = score(c, f, "1-minute", "1m")
    fun score5m(c: List<Candle>, f: FeedStatus) = score(c, f, "5-minute", "5m")
    fun score15m(c: List<Candle>, f: FeedStatus, fiveDirection: Int = 0) =
        score(c, f, "15-minute", "15m")

    fun timeframeBias(s: TimeframeScore): Verdict =
        if (s.connection != ConnectionState.CONNECTED || s.candleCount < MIN_CANDLES) Verdict.DNT
        else when {
            s.score > 0 -> Verdict.UP
            s.score < 0 -> Verdict.DOWN
            else -> Verdict.DNT
        }

    fun decide(
        one: TimeframeScore,
        five: TimeframeScore,
        fifteen: TimeframeScore,
        dntThreshold: Int = DEFAULT_DNT_THRESHOLD,
    ): Decision {
        val problems = listOf(one, five, fifteen)
            .filter { it.connection != ConnectionState.CONNECTED || it.candleCount < MIN_CANDLES }
            .map { "${it.label}: data unavailable." }
        val total = (one.score + five.score + fifteen.score).coerceIn(-10, 10)
        val verdict = if (problems.isNotEmpty()) Verdict.DNT else when {
            total >= DEFAULT_DNT_THRESHOLD -> Verdict.UP
            total <= -DEFAULT_DNT_THRESHOLD -> Verdict.DOWN
            else -> Verdict.DNT
        }
        return Decision(verdict, "Compatibility decision", one, five, fifteen, problems, total, DEFAULT_DNT_THRESHOLD)
    }

    fun evaluateTarget(
        one: List<Candle>,
        five: List<Candle>,
        fifteen: List<Candle>,
        feeds: List<FeedStatus>,
        target: Double?,
        price: Double?,
        threshold: Int = DEFAULT_DNT_THRESHOLD,
    ): TargetEvaluation {
        val problems = mutableListOf<String>()
        val labels = listOf("1-minute", "5-minute", "15-minute")
        val sets = listOf(one, five, fifteen)
        sets.forEachIndexed { i, candles ->
            if (candles.count { it.closed } < MIN_CANDLES) {
                problems += "${labels[i]}: need $MIN_CANDLES completed candles; received ${candles.count { it.closed }}."
            }
        }
        feeds.forEachIndexed { i, feed ->
            if (feed.state != ConnectionState.CONNECTED) {
                problems += "${labels[i]} feed unavailable: ${feed.lastError ?: feed.detail}."
            }
        }
        if (target == null || !target.isFinite() || target <= 0) problems += "Waiting for the active 15-minute target."
        if (price == null || !price.isFinite() || price <= 0) problems += "Waiting for current BTC price."
        if (problems.isNotEmpty()) {
            return TargetEvaluation(0, Verdict.DNT, 0.0, "DNT: required market data is not ready.", problems)
        }

        val oneMinute = one.filter { it.closed }.sortedBy { it.openTime }
        val fiveMinute = five.filter { it.closed }.sortedBy { it.openTime }
        val fifteenMinute = fifteen.filter { it.closed }.sortedBy { it.openTime }
        val current = price!!
        val baseline = target!!

        // Volatility-aware stand-off: minimum 0.05% of target or 25% of recent 15m ATR.
        val recent15 = fifteenMinute.takeLast(15)
        val trueRanges = recent15.drop(1).mapIndexed { index, candle ->
            val previousClose = recent15[index].close
            max(candle.high - candle.low, max(abs(candle.high - previousClose), abs(candle.low - previousClose)))
        }
        val atr15 = trueRanges.average().takeIf { it.isFinite() } ?: 0.0
        val buffer = max(baseline * 0.0005, atr15 * 0.25)
        val longBias = current < baseline - buffer
        val shortBias = current > baseline + buffer
        val inBuffer = !longBias && !shortBias

        // 1m reversal trigger: inspect five consecutive closed candles and compare their
        // volume with the five candles immediately before the cluster.
        val cluster = oneMinute.takeLast(5)
        val volumeBaseline = oneMinute.dropLast(5).takeLast(5).map { it.volume }.average()
        val clusterVolume = cluster.map { it.volume }.average()
        val volumeExpanded = volumeBaseline > 0.0 && clusterVolume >= volumeBaseline * 1.20
        val last = cluster.last()
        val prior = cluster.dropLast(1)
        val greenCount = cluster.count { it.close > it.open }
        val redCount = cluster.count { it.close < it.open }
        val risingCloses = cluster.takeLast(3).zipWithNext().all { it.second.close > it.first.close }
        val fallingCloses = cluster.takeLast(3).zipWithNext().all { it.second.close < it.first.close }
        val earlySellPressure = cluster[1].close < cluster[0].close
        val earlyBuyPressure = cluster[1].close > cluster[0].close
        val bullishBreak = earlySellPressure && risingCloses && last.close > prior.maxOf { it.high } && last.close > last.open
        val bearishBreak = earlyBuyPressure && fallingCloses && last.close < prior.minOf { it.low } && last.close < last.open
        val bullishAbsorption = cluster.dropLast(1).any {
            val body = abs(it.close - it.open).coerceAtLeast(0.0000001)
            val lowerWick = minOf(it.open, it.close) - it.low
            lowerWick >= body * 1.25
        } && earlySellPressure && risingCloses && greenCount >= 3 && last.close > cluster.first().high
        val bearishAbsorption = cluster.dropLast(1).any {
            val body = abs(it.close - it.open).coerceAtLeast(0.0000001)
            val upperWick = it.high - maxOf(it.open, it.close)
            upperWick >= body * 1.25
        } && earlyBuyPressure && fallingCloses && redCount >= 3 && last.close < cluster.first().low

        val oneMinutePoints = when {
            volumeExpanded && risingCloses && greenCount >= 3 &&
                (bullishBreak || bullishAbsorption) -> 5
            volumeExpanded && fallingCloses && redCount >= 3 &&
                (bearishBreak || bearishAbsorption) -> -5
            else -> 0
        }

        // 5m confirmation: three-candle higher-high/higher-low or lower-high/lower-low
        // structure, directional closes, and expanding volume, only in the target-side bias.
        val fiveCluster = fiveMinute.takeLast(3)
        val fiveVolumeBase = fiveMinute.dropLast(3).takeLast(3).map { it.volume }.average()
        val fiveVolumeNow = fiveCluster.map { it.volume }.average()
        val fiveVolumeExpanded = fiveVolumeBase > 0.0 && fiveVolumeNow >= fiveVolumeBase * 1.05
        val fiveUp = fiveCluster.size == 3 &&
            fiveCluster.zipWithNext().all { (a, b) -> b.high > a.high && b.low > a.low && b.close > a.close } &&
            fiveCluster.count { it.close > it.open } >= 2
        val fiveDown = fiveCluster.size == 3 &&
            fiveCluster.zipWithNext().all { (a, b) -> b.high < a.high && b.low < a.low && b.close < a.close } &&
            fiveCluster.count { it.close < it.open } >= 2
        val fiveMinutePoints = when {
            longBias && fiveUp && fiveVolumeExpanded -> 3
            shortBias && fiveDown && fiveVolumeExpanded -> -3
            else -> 0
        }

        // 15m target engine: +2 when outside the buffer and the last three 15m closes
        // are moving toward the target; -2 when inside the buffer or moving away.
        val closes15 = fifteenMinute.takeLast(3).map { it.close }
        val movingUp = closes15.size == 3 && closes15[2] > closes15[1] && closes15[1] >= closes15[0]
        val movingDown = closes15.size == 3 && closes15[2] < closes15[1] && closes15[1] <= closes15[0]
        val movingTowardTarget = (longBias && movingUp) || (shortBias && movingDown)
        val targetPoints = if (!inBuffer && movingTowardTarget) 2 else -2

        val total = oneMinutePoints + fiveMinutePoints + targetPoints
        val verdict = when {
            total >= DEFAULT_DNT_THRESHOLD && longBias && oneMinutePoints > 0 -> Verdict.UP
            total <= -DEFAULT_DNT_THRESHOLD && shortBias && oneMinutePoints < 0 -> Verdict.DOWN
            else -> Verdict.DNT
        }
        val reason = "1m ${signed(oneMinutePoints)}, 5m ${signed(fiveMinutePoints)}, 15m target ${signed(targetPoints)}; buffer ${fmtPrice(buffer)}."
        val explanation = when (verdict) {
            Verdict.UP -> "UP: bullish reversal below target. $reason"
            Verdict.DOWN -> "DOWN: bearish reversal above target. $reason"
            Verdict.DNT -> "DNT: score, reversal confirmation, or target-side buffer does not qualify. $reason"
        }
        return TargetEvaluation(total.coerceIn(-10, 10), verdict, buffer, explanation, emptyList(), oneMinutePoints, fiveMinutePoints, targetPoints)
    }

    private fun score(c: List<Candle>, f: FeedStatus, label: String, interval: String): TimeframeScore {
        val data = c.filter { it.closed }.sortedBy { it.openTime }
        val closes = data.map { it.close }
        val price = closes.lastOrNull()
        val ema = if (price == null) null else Indicators.ema(closes, 9)
        val rsi = if (price == null) null else Indicators.rsi(closes)
        val points = when {
            price == null || ema == null -> 0
            price > ema -> 1
            price < ema -> -1
            else -> 0
        }
        val components = if (data.size < MIN_CANDLES) {
            listOf(ComponentScore("Data", 0, "Need $MIN_CANDLES completed candles, have ${data.size}."))
        } else {
            listOf(ComponentScore("EMA9 context", points, "Price ${fmtPrice(price)} vs EMA9 ${fmtPrice(ema)}."))
        }
        val lastCandle = data.lastOrNull()
        return TimeframeScore(
            label, interval, points, components, price, ema, null, rsi, null, null, null,
            if (lastCandle == null || lastCandle.close >= lastCandle.open) "last candle up" else "last candle down",
            Indicators.volumeRatio(data), f.state, f.detail, f.updatedAt, f.lastError, data.size,
        )
    }
}