package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Target-aware trend-leg projection using price swings, volume participation and MACD histogram. */
data class EarlySignalResult(
    val label: String,
    val targetPosition: String,
    val detail: String,
)

object EarlyReversalDetector {
    private data class Leg(
        val direction: Int,
        val move: Double,
        val minutes: Double,
        val averageVolume: Double,
    )

    fun evaluate(
        candles: List<Candle>,
        momentum: List<Double>,
        priceEma: List<Double>,
        targetPrice: Double?,
        emaPeriod: Int = 3,
        fiveMinuteCandles: List<Candle> = emptyList(),
        marketEndTimeMs: Long? = null,
        orderBook: OrderBookSnapshot? = null,
        orderBookHistory: List<OrderBookSnapshot> = emptyList(),
        nowMs: Long = System.currentTimeMillis(),
    ): EarlySignalResult {
        if (candles.size < 6 || momentum.size != candles.size || priceEma.size != candles.size) {
            return EarlySignalResult("WAIT • COLLECTING DATA", "TARGET UNKNOWN",
                "Waiting for at least 6 one-minute candles.")
        }

        val lastIndex = candles.lastIndex
        val price = candles[lastIndex].close
        if (targetPrice == null || !targetPrice.isFinite() || !price.isFinite()) {
            return EarlySignalResult("WAIT • TARGET UNAVAILABLE", "TARGET UNKNOWN",
                "Waiting for a valid live price and 15-minute target.")
        }

        val distance = targetPrice - price
        val targetPosition = when {
            distance < 0.0 -> "ABOVE TARGET"
            distance > 0.0 -> "BELOW TARGET"
            else -> "AT TARGET"
        }
        val endTime = marketEndTimeMs ?: ((nowMs / FIFTEEN_MINUTES_MS) + 1L) * FIFTEEN_MINUTES_MS
        val minutesRemaining = ((endTime - nowMs).coerceAtLeast(0L)) / 60_000.0
        if (minutesRemaining <= 0.0) {
            return EarlySignalResult("WAIT • NEXT MARKET TARGET", targetPosition,
                "The current 15-minute target window has ended.")
        }
        val legs = buildLegs(candles)
        val fiveCloses = fiveMinuteCandles.map { it.close }
        val fiveEma3 = Indicators.emaSeriesFromFirst(fiveCloses, 3).lastOrNull()
        val fiveEma99 = Indicators.emaSeriesFromFirst(fiveCloses, 99).lastOrNull()
        val fiveMinuteDirection = when {
            fiveEma3 != null && fiveEma99 != null && fiveEma3 > fiveEma99 -> 1
            fiveEma3 != null && fiveEma99 != null && fiveEma3 < fiveEma99 -> -1
            else -> 0
        }
        val rsi = calculateRsi(candles.map { it.close }, 14)
        val adx = calculateAdx(candles, 14)
        val divergence = detectRsiDivergence(candles, calculateRsiSeries(candles.map { it.close }, 14))
        val currentLeg = legs.lastOrNull()
        if (currentLeg == null || currentLeg.move <= 0.0) {
            return EarlySignalResult("WAIT • NO CLEAR PRICE LEG", targetPosition,
                "Waiting for a measurable directional price move.")
        }

        // Detect panic flow being absorbed instead of treating every small counter-move as noise.
        // Binance kline taker-buy volume lets us estimate aggressive selling/buying; retained
        // order-book samples show whether nearby liquidity is replenishing or disappearing.
        val recentFlow = candles.takeLast(3)
        val flowVolume = recentFlow.sumOf { it.volume }
        val takerBuy = recentFlow.sumOf { it.takerBuyVolume.coerceIn(0.0, it.volume) }
        val takerSell = (flowVolume - takerBuy).coerceAtLeast(0.0)
        val sellShare = if (flowVolume > 0.0) takerSell / flowVolume else 0.5
        val buyShare = if (flowVolume > 0.0) takerBuy / flowVolume else 0.5
        val bookSamples = orderBookHistory.takeLast(6)
        val firstBook = bookSamples.firstOrNull()
        val lastBook = orderBook ?: bookSamples.lastOrNull()
        val bidDepthChange = if (firstBook != null && firstBook.nearBidNotional > 0.0 && lastBook != null)
            lastBook.nearBidNotional / firstBook.nearBidNotional else 1.0
        val askDepthChange = if (firstBook != null && firstBook.nearAskNotional > 0.0 && lastBook != null)
            lastBook.nearAskNotional / firstBook.nearAskNotional else 1.0
        val bookImbalance = lastBook?.imbalance ?: 0.0
        val last = candles.last()
        val previous = candles[candles.lastIndex - 1]
        val lastRange = (last.high - last.low).coerceAtLeast(0.0)
        val closeInRange = if (lastRange > 0.0) (last.close - last.low) / lastRange else 0.5
        val sellPressure = sellShare >= 0.56
        val buyPressure = buyShare >= 0.56
        val bidsAbsorbing = bidDepthChange >= 0.98 && bookImbalance >= 0.02
        val asksAbsorbing = askDepthChange >= 0.98 && bookImbalance <= -0.02
        val sellerExhaustion = sellPressure && bidsAbsorbing &&
            (last.close >= previous.close || closeInRange >= 0.68 || last.low >= previous.low)
        val buyerExhaustion = buyPressure && asksAbsorbing &&
            (last.close <= previous.close || closeInRange <= 0.32 || last.high <= previous.high)
        val absorptionDirection = when {
            sellerExhaustion -> 1
            buyerExhaustion -> -1
            else -> 0
        }
        val direction = if (absorptionDirection != 0) absorptionDirection else currentLeg.direction
        val sameDirection = legs.filter { it.direction == direction }.takeLast(3)
        val reversalMove = abs(last.close - previous.close).coerceAtLeast(lastRange * 0.10)
        val historicalLegSize = if (sameDirection.isEmpty()) {
            if (absorptionDirection != 0) reversalMove else currentLeg.move
        } else sameDirection.map { it.move }.average()
        val historicalLegSpeed = if (sameDirection.isEmpty()) 0.0
            else sameDirection.map { it.move / it.minutes.coerceAtLeast(1.0) }.average()
        val priorLegs = legs.dropLast(1).filter { it.direction != direction }.takeLast(3)
        val pullbackAverage = priorLegs.map { it.move }.averageOrZero()
        val pullbackRatio = if (historicalLegSize > 0.0 && priorLegs.isNotEmpty())
            pullbackAverage / historicalLegSize else 0.0

        // Compare current directional-leg volume with recent candles. Clamp the adjustment
        // so one unusual volume bar cannot dominate the forecast.
        val recentVolume = candles.takeLast(3).map { it.volume }.average()
        val baselineVolume = candles.dropLast(3).takeLast(12).map { it.volume }.average()
            .takeIf { it > 0.0 } ?: recentVolume
        val volumeRatio = if (baselineVolume > 0.0) recentVolume / baselineVolume else 1.0
        val volumeFactor = volumeRatio.coerceIn(0.75, 1.25)

        // Histogram strength is relative to its own recent range, not a fixed dollar threshold.
        val recentMomentum = momentum.takeLast(6)
        val momentumScale = recentMomentum.map { abs(it) }.average().takeIf { it > 0.0 } ?: 1.0
        val momentumStrength = (abs(momentum.last()) / momentumScale).coerceIn(0.75, 1.20)
        val momentumAgrees = (direction > 0 && momentum.last() >= 0.0) ||
            (direction < 0 && momentum.last() <= 0.0)
        val fiveMinuteAgrees = fiveMinuteDirection == direction
        val adxSupportsTrend = adx == null || adx >= 16.0
        val rsiSupportsDirection = if (direction > 0) (rsi == null || rsi in 42.0..75.0)
            else (rsi == null || rsi in 25.0..58.0)
        val divergenceAgainst = (direction > 0 && divergence == "BEARISH") ||
            (direction < 0 && divergence == "BULLISH")
        val momentumFactor = if (momentumAgrees) max(1.0, momentumStrength) else 0.80

        // A pullback consumes part of the preceding impulse; volume and momentum adjust
        // the expected next leg without allowing either indicator to invent direction.
        val continuationFactor = (1.0 - min(0.35, pullbackRatio * 0.25)).coerceIn(0.65, 1.0)
        val projectedMove = historicalLegSize * continuationFactor * volumeFactor * momentumFactor
        val activeLegSpeed = if (absorptionDirection != 0 && currentLeg.direction != direction)
            reversalMove else currentLeg.move / currentLeg.minutes.coerceAtLeast(1.0)
        val projectedSpeed = max(activeLegSpeed, historicalLegSpeed) * volumeFactor * momentumFactor
        val distanceToTarget = abs(distance)
        val activeBookImbalance = orderBook?.imbalance ?: 0.0
        val bookFactor = (1.0 + activeBookImbalance * 0.20).coerceIn(0.80, 1.20)
        val alignedBook = (direction > 0 && activeBookImbalance > 0.05) || (direction < 0 && activeBookImbalance < -0.05)
        val projectedSpeedWithBook = projectedSpeed * bookFactor
        val projectedExpiryPrice = price + direction * projectedSpeedWithBook * minutesRemaining
        val forecastSide = when { projectedExpiryPrice > targetPrice -> "ABOVE TARGET"; projectedExpiryPrice < targetPrice -> "BELOW TARGET"; else -> "AT TARGET" }
        val minutesToTarget = if (projectedSpeedWithBook > 0.0) distanceToTarget / projectedSpeedWithBook else Double.POSITIVE_INFINITY
        val likelyToFinishUp = projectedExpiryPrice > targetPrice
        val likelyToFinishDown = projectedExpiryPrice < targetPrice
        val likelyToReach = (direction > 0 && likelyToFinishUp) || (direction < 0 && likelyToFinishDown)
        val emaNow = priceEma[lastIndex]
        val emaPrevious = priceEma[lastIndex - 1]
        val emaTrend = if (emaNow > emaPrevious) "rising" else if (emaNow < emaPrevious) "falling" else "flat"
        // The target position controls which direction is allowed. Never show an UP
        // trend while price is above target, or a DOWN trend while price is below target.
        // A counter-direction absorption signal is useful evidence, but it is not a valid
        // trade setup until price action turns toward the target.
        val label = when (targetPosition) {
            "ABOVE TARGET" -> when {
                direction < 0 && likelyToFinishDown && fiveMinuteAgrees &&
                    adxSupportsTrend && rsiSupportsDirection && !divergenceAgainst &&
                    hasLateEntryConfirmation(candles, priceEma, -1) -> "EARLY DOWN TREND"
                else -> "WAIT • DOWN ENTRY NOT READY"
            }
            "BELOW TARGET" -> when {
                direction > 0 && likelyToFinishUp && fiveMinuteAgrees &&
                    adxSupportsTrend && rsiSupportsDirection && !divergenceAgainst &&
                    hasLateEntryConfirmation(candles, priceEma, 1) -> "EARLY UP TREND"
                else -> "WAIT • UP ENTRY NOT READY"
            }
            else -> "WAIT • PRICE AT TARGET"
        }
        val directionText = if (direction > 0) "UP" else "DOWN"
        val evidence = buildList {
            add("Actual price: " + String.format(java.util.Locale.US, "$%,.2f", price))
            add("Target distance: " + (if (distance >= 0) "below by " else "above by ") +
                String.format(java.util.Locale.US, "$%,.2f", abs(distance)))
            add("5m EMA3/EMA99 direction: " + when (fiveMinuteDirection) { 1 -> "UP"; -1 -> "DOWN"; else -> "NEUTRAL" })
            add("RSI(14): " + (rsi?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "warming up") +
                " • ADX(14): " + (adx?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "warming up") +
                " • RSI divergence: $divergence")
            add("Target: " + String.format(java.util.Locale.US, "$%,.2f", targetPrice))
            add("Current leg: $directionText " + String.format(java.util.Locale.US, "$%.2f", currentLeg.move))
            add("Typical $directionText leg: " + String.format(java.util.Locale.US, "$%.2f", historicalLegSize))
            add("Pullback: " + String.format(java.util.Locale.US, "$%.2f", pullbackAverage) +
                " (" + String.format(java.util.Locale.US, "%.0f%%", pullbackRatio * 100.0) + " of leg)")
            add("Volume: " + String.format(java.util.Locale.US, "%.2fx", volumeRatio) +
                " • momentum " + if (momentumAgrees) "supports" else "opposes")
            add("Aggressive flow: " + String.format(java.util.Locale.US, "%.0f%% sells / %.0f%% buys", sellShare * 100.0, buyShare * 100.0))
            add("Near-book depth change: bids " + String.format(java.util.Locale.US, "%+.1f%%", (bidDepthChange - 1.0) * 100.0) +
                " • asks " + String.format(java.util.Locale.US, "%+.1f%%", (askDepthChange - 1.0) * 100.0))
            add(if (sellerExhaustion) "PANIC SELL ABSORPTION • bids holding while sellers hit" else if (buyerExhaustion) "PANIC BUY ABSORPTION • asks holding while buyers lift" else "Absorption: not confirmed")
            add("Projected next move: " + String.format(java.util.Locale.US, "$%.2f", projectedMove))
            add("Distance needed: " + String.format(java.util.Locale.US, "$%.2f", distanceToTarget))
            add("Estimated time to target: " + String.format(java.util.Locale.US, "%.1f min", minutesToTarget))
            add("Time remaining: " + String.format(java.util.Locale.US, "%.1f min", minutesRemaining))
            add("Projected expiry price: " + String.format(java.util.Locale.US, "$%,.2f", projectedExpiryPrice))
            add("Projected result: $forecastSide")
            if (orderBook != null) {
                add((if (orderBook.smoothingWindowSeconds > 0) "${orderBook.smoothingWindowSeconds}-second" else "Live") + " smoothed near-book average: bids " +
                    String.format(java.util.Locale.US, "$%,.0f", orderBook.nearBidNotional) +
                    " • asks " + String.format(java.util.Locale.US, "$%,.0f", orderBook.nearAskNotional))
                add("Order book imbalance: " +
                    String.format(java.util.Locale.US, "%.1f%%", activeBookImbalance * 100.0) +
                    if (alignedBook) " • supports trend" else " • not confirming trend")
            } else add("Order book: unavailable")
            add("EMA 3: $emaTrend • 5m confirmation=$fiveMinuteAgrees")
            add("Entry timing: pullback/retest plus directional close required; otherwise WAIT")
            add("ADX=$adxSupportsTrend • RSI=$rsiSupportsDirection • divergenceAgainst=$divergenceAgainst")
        }.joinToString(" | ")
        return EarlySignalResult(label, targetPosition, evidence)
    }

    /** Groups consecutive one-minute close changes into directional impulse/pullback legs. */
    private fun buildLegs(candles: List<Candle>): List<Leg> {
        val result = mutableListOf<Leg>()
        if (candles.size < 2) return result
        var startIndex = 0
        var direction = 0
        for (index in 1 until candles.size) {
            val change = candles[index].close - candles[index - 1].close
            val nextDirection = when { change > 0.0 -> 1; change < 0.0 -> -1; else -> 0 }
            if (nextDirection == 0) continue
            if (direction == 0) {
                direction = nextDirection
                startIndex = index - 1
            } else if (nextDirection != direction) {
                addLeg(result, candles, startIndex, index - 1, direction)
                startIndex = index - 1
                direction = nextDirection
            }
        }
        if (direction != 0) addLeg(result, candles, startIndex, candles.lastIndex, direction)
        return result
    }

    private fun addLeg(result: MutableList<Leg>, candles: List<Candle>, start: Int, end: Int, direction: Int) {
        if (end <= start) return
        val move = abs(candles[end].close - candles[start].close)
        if (move <= 0.0) return
        val minutes = ((candles[end].openTime - candles[start].openTime).coerceAtLeast(60_000L) / 60_000.0)
        val volume = candles.subList(start + 1, end + 1).map { it.volume }.average()
        result.add(Leg(direction, move, minutes, volume))
    }

    private fun hasLateEntryConfirmation(candles: List<Candle>, ema: List<Double>, direction: Int): Boolean {
        if (candles.size < 4 || ema.size != candles.size) return false
        val touched = (candles.lastIndex - 2..candles.lastIndex).any { i ->
            abs(candles[i].close - ema[i]) <= max(candles[i].high - candles[i].low, candles[i].close * 0.00025)
        }
        val last = candles.last()
        val previous = candles[candles.lastIndex - 1]
        return touched && if (direction > 0) last.close > previous.close && last.close > ema.last()
            else last.close < previous.close && last.close < ema.last()
    }

    private fun calculateRsiSeries(closes: List<Double>, period: Int): List<Double> {
        if (closes.size <= period) return emptyList()
        val out = MutableList(closes.size) { 50.0 }
        var gains = 0.0; var losses = 0.0
        for (i in 1..period) {
            val change = closes[i] - closes[i - 1]
            if (change >= 0.0) gains += change else losses -= change
        }
        var avgGain = gains / period; var avgLoss = losses / period
        fun current(): Double = if (avgLoss <= 0.0) 100.0 else 100.0 - 100.0 / (1.0 + avgGain / avgLoss)
        out[period] = current()
        for (i in period + 1 until closes.size) {
            val change = closes[i] - closes[i - 1]
            avgGain = (avgGain * (period - 1) + maxOf(change, 0.0)) / period
            avgLoss = (avgLoss * (period - 1) + maxOf(-change, 0.0)) / period
            out[i] = current()
        }
        return out
    }

    private fun calculateRsi(closes: List<Double>, period: Int): Double? =
        calculateRsiSeries(closes, period).lastOrNull()

    private fun calculateAdx(candles: List<Candle>, period: Int): Double? {
        if (candles.size < period * 2 + 1) return null
        val dx = (candles.size - period until candles.size).mapNotNull { i ->
            val c = candles[i]; val p = candles[i - 1]
            val up = c.high - p.high; val down = p.low - c.low
            val plus = if (up > down && up > 0.0) up else 0.0
            val minus = if (down > up && down > 0.0) down else 0.0
            val tr = maxOf(c.high - c.low, abs(c.high - p.close), abs(c.low - p.close))
            if (tr <= 0.0) null else abs(plus - minus) / (plus + minus).coerceAtLeast(1e-12) * 100.0
        }
        return dx.takeIf { it.isNotEmpty() }?.average()
    }

    private fun detectRsiDivergence(candles: List<Candle>, rsi: List<Double>): String {
        if (candles.size < 12 || rsi.size != candles.size) return "NONE"
        val split = candles.size - 6
        val oldLow = (candles.size - 12 until split).minByOrNull { candles[it].low } ?: return "NONE"
        val newLow = (split until candles.size).minByOrNull { candles[it].low } ?: return "NONE"
        val oldHigh = (candles.size - 12 until split).maxByOrNull { candles[it].high } ?: return "NONE"
        val newHigh = (split until candles.size).maxByOrNull { candles[it].high } ?: return "NONE"
        return when {
            candles[newLow].low < candles[oldLow].low && rsi[newLow] > rsi[oldLow] -> "BULLISH"
            candles[newHigh].high > candles[oldHigh].high && rsi[newHigh] < rsi[oldHigh] -> "BEARISH"
            else -> "NONE"
        }
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private const val FIFTEEN_MINUTES_MS = 15L * 60L * 1000L
}
