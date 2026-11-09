package com.decisionoverlay.checker

import kotlin.math.abs

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
    const val DEFAULT_DNT_THRESHOLD = 2
    const val MIN_DNT_THRESHOLD = 1
    const val MAX_DNT_THRESHOLD = 5

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
        val activeThreshold = dntThreshold.coerceIn(MIN_DNT_THRESHOLD, MAX_DNT_THRESHOLD)
        val verdict = if (problems.isNotEmpty()) Verdict.DNT else when {
            total >= activeThreshold -> Verdict.UP
            total <= -activeThreshold -> Verdict.DOWN
            else -> Verdict.DNT
        }
        return Decision(verdict, "Compatibility decision", one, five, fifteen, problems, total, activeThreshold)
    }

    fun evaluateTarget(
        one: List<Candle>,
        five: List<Candle>,
        fifteen: List<Candle>,
        feeds: List<FeedStatus>,
        target: Double?,
        price: Double?,
        threshold: Int = DEFAULT_DNT_THRESHOLD,
        strictnessLevel: Int = 1,
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

        // The target is a binary finish-line only: no dollar buffer or distance threshold.
        // Below target, only a confirmed bullish reversal can signal UP. Above target,
        // only a confirmed bearish reversal can signal DOWN.
        val expectedDirection = when {
            current < baseline -> 1
            current > baseline -> -1
            else -> 0
        }
        if (expectedDirection == 0) {
            return TargetEvaluation(0, Verdict.DNT, 0.0, "DNT: price is sitting on the binary target; wait for a clear side.", emptyList())
        }

        val profile = StrictnessProfile.fromLevel(strictnessLevel)
        val activeThreshold = profile.scoreThreshold

        // 1m exhaustion: price presses into a fresh local extreme, then closes back
        // through nearby structure. Momentum divergence is measured by improving MACD
        // histogram while price makes a new local extreme.
        val recent = oneMinute.takeLast(5)
        val prior = oneMinute.dropLast(5).takeLast(5)
        val closes = oneMinute.map { it.close }
        val currentMacd = Indicators.macd(closes)
        val previousMacd = Indicators.macd(oneMinute.dropLast(5).map { it.close })
        val localLowIndex = recent.indices.minByOrNull { recent[it].low }
        val localHighIndex = recent.indices.maxByOrNull { recent[it].high }
        val bullishExtreme = recent.size == 5 && localLowIndex != null &&
            localLowIndex < recent.lastIndex &&
            recent.last().close > recent.last().open &&
            recent.last().close > recent[localLowIndex].close
        val bearishExtreme = recent.size == 5 && localHighIndex != null &&
            localHighIndex < recent.lastIndex &&
            recent.last().close < recent.last().open &&
            recent.last().close < recent[localHighIndex].close
        val bullishBreak = recent.size == 5 && recent.last().close > recent.dropLast(1).maxOf { it.high }
        val bearishBreak = recent.size == 5 && recent.last().close < recent.dropLast(1).minOf { it.low }
        val bullishDivergence = currentMacd != null && previousMacd != null &&
            recent.minOf { it.low } <= prior.minOf { it.low } &&
            currentMacd.histogram > previousMacd.histogram
        val bearishDivergence = currentMacd != null && previousMacd != null &&
            recent.maxOf { it.high } >= prior.maxOf { it.high } &&
            currentMacd.histogram < previousMacd.histogram

        val baselineVolume = prior.map { it.volume }.average()
        val recentVolume = recent.map { it.volume }.average()
        val volumeExpanded = baselineVolume > 0.0 &&
            recentVolume >= baselineVolume * profile.volumeExpansionRatio
        val volumeDrying = baselineVolume > 0.0 &&
            recentVolume <= baselineVolume * profile.volumeDryUpRatio
        val volumeConfirmed = volumeExpanded || volumeDrying

        val bullishExhaustion = bullishExtreme && (bullishBreak || bullishDivergence)
        val bearishExhaustion = bearishExtreme && (bearishBreak || bearishDivergence)
        val oneMinuteConfirmations = when (expectedDirection) {
            1 -> listOf(bullishExtreme, bullishDivergence, bullishBreak, volumeConfirmed).count { it }
            else -> listOf(bearishExtreme, bearishDivergence, bearishBreak, volumeConfirmed).count { it }
        }
        val oneMinutePoints = when {
            expectedDirection == 1 && bullishExhaustion && volumeConfirmed -> 5
            expectedDirection == -1 && bearishExhaustion && volumeConfirmed -> -5
            else -> 0
        }

        // 5m structural confirmation: consecutive higher highs/lows or lower highs/lows.
        val fiveCluster = fiveMinute.takeLast(3)
        val fiveUp = fiveCluster.size == 3 &&
            fiveCluster.zipWithNext().all { (a, b) -> b.high > a.high && b.low > a.low && b.close > a.close } &&
            fiveCluster.count { it.close > it.open } >= 2
        val fiveDown = fiveCluster.size == 3 &&
            fiveCluster.zipWithNext().all { (a, b) -> b.high < a.high && b.low < a.low && b.close < a.close } &&
            fiveCluster.count { it.close < it.open } >= 2
        val fiveMinuteConfirmed = if (expectedDirection == 1) fiveUp else fiveDown
        val fiveMinutePoints = if (fiveMinuteConfirmed) expectedDirection * 3 else 0

        // 15m is contextual structure, not a fixed score bias or dollar-distance rule.
        val macro = fifteenMinute.takeLast(3)
        val macroUp = macro.size == 3 &&
            macro.zipWithNext().all { (a, b) -> b.high > a.high && b.low > a.low && b.close > a.close }
        val macroDown = macro.size == 3 &&
            macro.zipWithNext().all { (a, b) -> b.high < a.high && b.low < a.low && b.close < a.close }
        val macroConfirmed = if (expectedDirection == 1) macroUp else macroDown
        val targetPoints = if (macroConfirmed) expectedDirection * 2 else 0

        val oneMinuteConfirmed = oneMinutePoints != 0
        val confirmationCount = oneMinuteConfirmations +
            (if (fiveMinuteConfirmed) 1 else 0) +
            (if (macroConfirmed) 1 else 0)
        val total = oneMinutePoints + fiveMinutePoints + targetPoints
        val enoughConfirmations = confirmationCount >= profile.requiredConfirmations
        val verdict = when {
            expectedDirection == 1 && oneMinuteConfirmed && enoughConfirmations && total >= activeThreshold -> Verdict.UP
            expectedDirection == -1 && oneMinuteConfirmed && enoughConfirmations && total <= -activeThreshold -> Verdict.DOWN
            else -> Verdict.DNT
        }

        val side = if (expectedDirection > 0) "below target" else "above target"
        val reason = "Target side: $side. 1m exhaustion ${signed(oneMinutePoints)}, 5m structure ${signed(fiveMinutePoints)}, 15m context ${signed(targetPoints)}. Confirmations $confirmationCount/${profile.requiredConfirmations}; volume ${if (volumeExpanded) "expanded" else if (volumeDrying) "drying" else "unconfirmed"}; MACD divergence ${if ((expectedDirection > 0 && bullishDivergence) || (expectedDirection < 0 && bearishDivergence)) "yes" else "no"}; score threshold ±$activeThreshold."
        val explanation = when (verdict) {
            Verdict.UP -> "UP: bullish reversal confirmed while BTC is below the target. $reason"
            Verdict.DOWN -> "DOWN: bearish reversal confirmed while BTC is above the target. $reason"
            Verdict.DNT -> "DNT: reversal, volume, structure, or strictness confirmations are incomplete. $reason"
        }
        return TargetEvaluation(total.coerceIn(-10, 10), verdict, 0.0, explanation, emptyList(), oneMinutePoints, fiveMinutePoints, targetPoints)
    }

    private fun score(c: List<Candle>, f: FeedStatus, label: String, interval: String): TimeframeScore {
        val data = c.filter { it.closed }.sortedBy { it.openTime }
        val closes = data.map { it.close }
        val price = closes.lastOrNull()
        val ema = if (price == null) null else Indicators.ema(closes, 9)
        val rsi = if (price == null) null else Indicators.rsi(closes)
        val macd = Indicators.macd(closes)
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
            label, interval, points, components, price, ema, null, rsi,
            macd?.line, macd?.signal, macd?.histogram,
            if (lastCandle == null || lastCandle.close >= lastCandle.open) "last candle up" else "last candle down",
            Indicators.volumeRatio(data), f.state, f.detail, f.updatedAt, f.lastError, data.size,
        )
    }
}
