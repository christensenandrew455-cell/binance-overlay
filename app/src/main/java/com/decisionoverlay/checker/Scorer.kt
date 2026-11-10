package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.round
import kotlin.math.roundToInt

data class TargetEvaluation(
    val score: Int,
    val candidate: Verdict,
    val buffer: Double,
    val reason: String,
    val problems: List<String>,
    val oneMinutePoints: Int = 0,
    val fiveMinutePoints: Int = 0,
    val targetPoints: Int = 0,
    val steepnessScore: Double? = null,
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


    /**
     * Uses the latest 1-hour candle's move, normalized by population
     * standard deviation of the latest 24 close-to-close hourly returns.
     */
    fun evaluateHourlySteepness(candles: List<Candle>, feed: FeedStatus): TargetEvaluation {
        val data = candles.sortedBy { it.openTime }
        val problems = mutableListOf<String>()
        if (feed.state != ConnectionState.CONNECTED) {
            problems += "1-hour BTC feed unavailable: ${feed.lastError ?: feed.detail}."
        }
        if (data.size < 25) {
            problems += "Need at least 25 hourly candles for 24 returns; received ${data.size}."
        }
        if (problems.isNotEmpty()) {
            return TargetEvaluation(0, Verdict.DNT, 0.0,
                "DNT: waiting for 1-hour candles and volatility history.", problems)
        }
        val latest = data.last()
        if (latest.open <= 0.0) {
            return TargetEvaluation(0, Verdict.DNT, 0.0,
                "DNT: latest hourly candle has an invalid opening price.",
                listOf("Invalid 1-hour opening price."))
        }
        val priceChangePct = ((latest.close - latest.open) / latest.open) * 100.0
        val closes = data.map { it.close }
        val returns = closes.zipWithNext { previous, current ->
            if (previous != 0.0) (current - previous) / previous else 0.0
        }.takeLast(24)
        val mean = returns.average()
        val historicalStd = sqrt(returns.map { (it - mean) * (it - mean) }.average()) * 100.0
        val denominator = if (historicalStd > 0.0) historicalStd else 1.0
        val steepness = minOf(10.0, round((kotlin.math.abs(priceChangePct) / denominator * 2.5) * 10.0) / 10.0)
        val direction = if (priceChangePct > 0.0) Verdict.UP else Verdict.DOWN
        val verdict = if (steepness < 4.0) Verdict.DNT else direction
        val directionText = if (direction == Verdict.UP) "UP" else "DOWN"
        val reason = "1H candle change=${String.format(java.util.Locale.US, "%.4f", priceChangePct)}%; " +
            "24-return volatility=${String.format(java.util.Locale.US, "%.4f", historicalStd)}%; " +
            "steepness=${String.format(java.util.Locale.US, "%.1f", steepness)}/10 (minimum 4.0); " +
            "hourly direction=$directionText."
        val explanation = when (verdict) {
            Verdict.UP -> "UP — 1-hour move is steep enough. $reason"
            Verdict.DOWN -> "DOWN — 1-hour move is steep enough. $reason"
            Verdict.DNT -> "DNT — 1-hour move is not steep enough. $reason"
        }
        return TargetEvaluation(steepness.roundToInt(), verdict, 0.0, explanation, emptyList(),
            steepnessScore = steepness)
    }

    /**
     * One-minute pullback strategy. UP means buy the dip in an uptrend;
     * DOWN means a short/sell setup after an upward retracement in a downtrend.
     */
    fun evaluatePullback(candles: List<Candle>, feed: FeedStatus, strictnessLevel: Int = 1, targetPrice: Double? = null, currentPrice: Double? = null): TargetEvaluation {
        val data = candles.filter { it.closed }.sortedBy { it.openTime }.takeLast(100)
        val problems = mutableListOf<String>()
        if (feed.state != ConnectionState.CONNECTED) problems += "1-minute BTC feed unavailable: ${feed.lastError ?: feed.detail}."
        if (data.size < 100) problems += "Need exactly 100 completed 1-minute candles; received ${data.size}."
        if (targetPrice == null || !targetPrice.isFinite() || targetPrice <= 0.0) problems += "Waiting for the active quarter-hour checkpoint target."
        if (currentPrice == null || !currentPrice.isFinite() || currentPrice <= 0.0) problems += "Waiting for the latest BTC market price."
        if (problems.isNotEmpty()) return TargetEvaluation(0, Verdict.DNT, 0.0, "DNT: waiting for 100 completed 1-minute candles, the active checkpoint target, and the latest BTC price.", problems)
        val closes = data.map { it.close }
        val current = data.last()
        val previous = data[data.lastIndex - 1]
        val ema9 = Indicators.emaSeriesFromFirst(closes, 9)
        val ema21 = Indicators.emaSeriesFromFirst(closes, 21)
        if (ema9.size < 2 || ema21.size < 2) return TargetEvaluation(0, Verdict.DNT, 0.0, "DNT: EMA history is not ready.", listOf("Unable to calculate EMA 9/21."))
        var gains = 0.0
        var losses = 0.0
        for (i in (closes.size - 14) until closes.size) {
            val change = closes[i] - closes[i - 1]
            if (change > 0.0) gains += change else losses -= change
        }
        val averageGain = gains / 14.0
        val averageLoss = losses / 14.0
        val rsi = when {
            averageLoss == 0.0 && averageGain == 0.0 -> 50.0
            averageLoss == 0.0 -> 100.0
            else -> 100.0 - (100.0 / (1.0 + averageGain / averageLoss))
        }
        val priorClose = closes[closes.lastIndex - 3]
        val roc = if (priorClose != 0.0) ((closes.last() - priorClose) / priorClose) * 100.0 else 0.0
        var score = if (ema9.last() > ema21.last()) 2 else -2
        if (rsi > 55.0) score += 1 else if (rsi < 45.0) score -= 1
        if (roc > 0.0) score += 1 else score -= 1
        val trend = when {
            score >= 3 -> "UPWARD"
            score <= -3 -> "DOWNWARD"
            else -> "NEUTRAL"
        }
        val bullishReversal = rsi < 30.0 && current.close > previous.close && ema9.last() > ema9[ema9.lastIndex - 1]
        val bearishReversal = rsi > 70.0 && current.close < previous.close && ema9.last() < ema9[ema9.lastIndex - 1]
        val target = targetPrice!!
        val livePrice = currentPrice!!
        val aboveTarget = livePrice > target
        val verdict = when {
            trend == "UPWARD" && !aboveTarget && !bearishReversal -> Verdict.UP
            trend == "DOWNWARD" && aboveTarget && !bullishReversal -> Verdict.DOWN
            else -> Verdict.DNT
        }
        val signal = when (verdict) {
            Verdict.UP -> "UP — upward trend while the current BTC price is at or below the active checkpoint target"
            Verdict.DOWN -> "DOWN — downward trend while the current BTC price is above the active checkpoint target"
            Verdict.DNT -> "DNT — trend, current-price side of target, or reversal filter does not allow an entry"
        }
        val reason = "$signal | Trend=$trend (score $score); current BTC=${fmtPrice(livePrice)}; active checkpoint target=${fmtPrice(target)}; target side=${if (aboveTarget) "ABOVE" else if (livePrice < target) "BELOW" else "AT"}; EMA9=${fmtPrice(ema9.last())}; EMA21=${fmtPrice(ema21.last())}; RSI14=${String.format(java.util.Locale.US, "%.2f", rsi)}; ROC3=${String.format(java.util.Locale.US, "%.4f", roc)}%; reversal=${if (bullishReversal) "BULLISH" else if (bearishReversal) "BEARISH" else "NONE"}. "
        return TargetEvaluation(score, verdict, livePrice - target, reason, emptyList(), score, 0, 0)
    }

    private fun score(c: List<Candle>, f: FeedStatus, label: String, interval: String): TimeframeScore {
        val data = c.filter { it.closed }.sortedBy { it.openTime }
        val closes = data.map { it.close }
        val price = closes.lastOrNull()
        val ema7 = Indicators.ema(closes, 7)
        val ema25 = Indicators.emaSeriesFromFirst(closes, 21).lastOrNull()
        val ema99 = Indicators.ema(closes, 99)
        val ema9 = Indicators.emaSeriesFromFirst(closes, 9).lastOrNull()
        val rsi = Indicators.rsi(closes)
        val macd = Indicators.macd(closes)
        val volumeRatio = Indicators.volumeRatio(data)
        val points = when {
            price == null || ema7 == null || ema25 == null || ema99 == null -> 0
            price > ema7 && ema7 > ema25 && ema25 > ema99 -> 3
            price < ema7 && ema7 < ema25 && ema25 < ema99 -> -3
            price > ema7 -> 1
            price < ema7 -> -1
            else -> 0
        }
        val components = if (data.size < MIN_CANDLES) {
            listOf(ComponentScore("Data", 0, "Need $MIN_CANDLES completed candles, have ${data.size}."))
        } else {
            listOf(
                ComponentScore("EMA 9/21", points, "Price ${fmtPrice(price)}; EMA9 ${fmtPrice(ema9)}, EMA21 ${fmtPrice(ema25)}."),
                ComponentScore("RSI(14)", if (rsi != null && rsi > 50) 1 else if (rsi != null && rsi < 50) -1 else 0, "RSI ${fmtNum(rsi)}."),
                ComponentScore("MACD", if (macd != null && macd.histogram > 0) 1 else if (macd != null && macd.histogram < 0) -1 else 0, "Histogram ${fmtNum(macd?.histogram)}."),
                ComponentScore("Volume", if (volumeRatio != null && volumeRatio >= 1.15) 1 else 0, "Current / 20-candle average ${fmtNum(volumeRatio)}x.")
            )
        }
        val lastCandle = data.lastOrNull()
        return TimeframeScore(
            label, interval, points, components, price, ema9, ema25, rsi,
            macd?.line, macd?.signal, macd?.histogram,
            if (lastCandle == null || lastCandle.close >= lastCandle.open) "last candle up" else "last candle down",
            volumeRatio, f.state, f.detail, f.updatedAt, f.lastError, data.size,
            ema7, ema25, ema99, if (interval == "1m") data else emptyList(),
        )
    }
}
