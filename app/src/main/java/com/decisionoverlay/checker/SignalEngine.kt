package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Read-only 15m Kalshi entry signal. The exchange price, order book and recent
 * executed-trade flow are Binance.US proxies, NOT CF Benchmarks settlement data.
 * "Power" is directional pressure (0..100), NEVER a calibrated win probability.
 */
object SignalEngine {
    data class Bar(
        val openedAt: Long, val closedAt: Long,
        val open: Double, val high: Double, val low: Double, val close: Double,
        val quoteVolume: Double
    )
    data class Level(val price: Double, val quantity: Double)
    data class Trade(
        val price: Double, val quantity: Double, val at: Long,
        val buyerWasMaker: Boolean
    )
    data class Snapshot(
        val coin: String,
        val marketTicker: String,
        val target: Double,
        val marketClosesAt: Long,
        val observedAt: Long,
        val spot: Double,
        val minuteBars: List<Bar>,
        val fiveMinuteBars: List<Bar>,
        val bids: List<Level>,
        val asks: List<Level>,
        val trades: List<Trade>
    )
    data class Result(
        val verdict: String,
        val reason: String,
        val report: String,
        val buyerPower: Int?,
        val sellerPower: Int?
    )

    fun targetAllows(direction: Int, spot: Double, target: Double): Boolean =
        if (direction == 1) spot < target else if (direction == -1) spot > target else false

    /** A rough volatility-based travel budget, NOT a probability or guarantee. */
    fun travelBudget(atr: Double, minutesLeft: Double, directionalSpeed: Double): Double =
        max(0.0, atr) * 1.10 * sqrt(max(0.0, minutesLeft)) +
            max(0.0, directionalSpeed) * max(0.0, minutesLeft) * 0.50

    fun evaluate(input: Snapshot): Result {
        val minutesLeft = (input.marketClosesAt - input.observedAt) / 60_000.0
        val gap = input.spot - input.target
        val header = String.format(Locale.US,
            "%s | %s\nBinance.US spot: %.8f\nKalshi target: %.8f\nGap: %+.8f (%.3f%%)\nTime left: %.2f min\n",
            input.coin, input.marketTicker, input.spot, input.target, gap,
            if (input.target > 0) gap / input.target * 100 else 0.0, minutesLeft)
        fun waitFor(reason: String, more: String = "", buyers: Int? = null): Result =
            Result("WAIT", reason, header + "WAIT: " + reason + "\n" + more,
                buyers, buyers?.let { 100 - it })

        if (input.target <= 0.0 || input.spot <= 0.0 ||
            !input.target.isFinite() || !input.spot.isFinite() ||
            minutesLeft <= 0.0 || minutesLeft > 15.1) return waitFor("No current valid market or price.")
        val one = input.minuteBars.filter { it.closedAt <= input.observedAt }
            .sortedBy { it.openedAt }
        val five = input.fiveMinuteBars.filter { it.closedAt <= input.observedAt }
            .sortedBy { it.openedAt }
        if (one.size < 115 || five.size < 115)
            return waitFor("Need at least 115 completed 1m AND 5m candles for EMA100.")
        if (input.observedAt - one.last().closedAt > 150_000 ||
            input.observedAt - five.last().closedAt > 420_000)
            return waitFor("Candle data stale; refusing to signal.")
        if (!continuous(one.takeLast(110), 60_000L) ||
            !continuous(five.takeLast(110), 300_000L))
            return waitFor("Missing 1m or 5m candles; refusing to interpolate.")

        val oneClose = one.map { it.close }
        val fiveClose = five.map { it.close }
        val fast1 = ema(oneClose, 4)
        val slow1 = ema(oneClose, 100)
        val fast5 = ema(fiveClose, 4)
        val slow5 = ema(fiveClose, 100)
        val atr = atr14(one)
        if (!atr.isFinite() || atr <= 0) return waitFor("Not enough volatility data.")
        val last = one.last()
        val lowPrev = one.subList(one.size - 11, one.size - 6)
        val lowRecent = one.subList(one.size - 6, one.size - 1)
        val recentHigh = lowRecent.maxOf { it.high }
        val previousHigh = lowPrev.maxOf { it.high }
        val recentLow = lowRecent.minOf { it.low }
        val previousLow = lowPrev.minOf { it.low }
        val bullStructure = recentHigh > previousHigh && recentLow > previousLow
        val bearStructure = recentHigh < previousHigh && recentLow < previousLow
        val upperWick = last.high - max(last.open, last.close)
        val lowerWick = min(last.open, last.close) - last.low
        val candleRange = max(1.0e-10, last.high - last.low)
        val wickBull = lowerWick / candleRange >= 0.35 && lowerWick > upperWick * 1.5
        val wickBear = upperWick / candleRange >= 0.35 && upperWick > lowerWick * 1.5
        val avgVolume = one.subList(one.size - 21, one.size - 1)
            .map { it.quoteVolume }.average()
        val volumeRatio = if (avgVolume > 0) last.quoteVolume / avgVolume else 0.0
        val delta3m = last.close - one[one.size - 4].close
        val momentumATR = delta3m / atr

        val fiveScore = listOf(
            sign(fast5.last() - slow5.last()),
            sign(five.last().close - slow5.last()),
            sign(fast5.last() - fast5[fast5.lastIndex - 1])
        ).sum()
        val fiveDirection = if (fiveScore >= 2) 1 else if (fiveScore <= -2) -1 else 0

        // Only depth near the actual market price, weighted by USD not coin count.
        val band = 0.002 // 0.20% on each side
        val bidDollars = input.bids.filter { it.price in (input.spot * (1 - band))..input.spot }
            .sumOf { it.price * it.quantity }
        val askDollars = input.asks.filter { it.price in input.spot..(input.spot * (1 + band)) }
            .sumOf { it.price * it.quantity }
        val depthTotal = bidDollars + askDollars
        if (depthTotal <= 0.0 || !depthTotal.isFinite())
            return waitFor("Order book unavailable or has no nearby depth.")

        // m=true means the BUYER was the MAKER: executed sell aggression.
        val recentTrades = input.trades.filter {
            it.at in (input.observedAt - 60_000L)..input.observedAt
        }
        val buyDollars = recentTrades.filter { !it.buyerWasMaker }
            .sumOf { it.price * it.quantity }
        val sellDollars = recentTrades.filter { it.buyerWasMaker }
            .sumOf { it.price * it.quantity }
        val flowTotal = buyDollars + sellDollars
        if (recentTrades.size < 8 || flowTotal <= 0 || !flowTotal.isFinite())
            return waitFor("Not enough recent executed trades to measure buyer/seller power.")
        val bookImbalance = ((bidDollars - askDollars) / depthTotal).coerceIn(-1.0, 1.0)
        val flowImbalance = ((buyDollars - sellDollars) / flowTotal).coerceIn(-1.0, 1.0)
        // Executed trades matter more than resting depth, which can be cancelled.
        val pressure = (0.70 * flowImbalance + 0.30 * bookImbalance).coerceIn(-1.0, 1.0)
        val buyerPower = ((pressure + 1) * 50).toInt().coerceIn(0, 100)
        val sellerPower = 100 - buyerPower

        val oneBull = score1m(1, one, fast1, slow1, momentumATR, volumeRatio,
            bullStructure, bearStructure, wickBull, wickBear)
        val oneBear = score1m(-1, one, fast1, slow1, momentumATR, volumeRatio,
            bullStructure, bearStructure, wickBull, wickBear)
        val slopePerMinute = delta3m / 3.0
        val estimatedMoveUp = travelBudget(atr, minutesLeft, slopePerMinute)
        val estimatedMoveDown = travelBudget(atr, minutesLeft, -slopePerMinute)
        val buffer = max(atr * 0.10, input.spot * 0.00005)
        val needed = abs(gap) + buffer
        val body = String.format(Locale.US,
            "Buyer power: %d%% | Seller power: %d%% (pressure score, not win odds)\n" +
            "Executed buy/sell last 60s: $%,.0f / $%,.0f (%d trades)\n" +
            "Near-book bids/asks (+/-0.20%%): $%,.0f / $%,.0f\n" +
            "Trade-flow imbalance: %+.1f%% | Book imbalance: %+.1f%%\n" +
            "1m EMA4/EMA100: %.8f / %.8f\n" +
            "5m EMA4/EMA100: %.8f / %.8f\n" +
            "5m direction: %s (%+d/3)\n" +
            "1m UP score: %d/7 | DOWN score: %d/7\n" +
            "1m price momentum (last 3 candles): %+.2f ATR\n" +
            "1m volume vs prior 20: %.2fx | ATR14: %.8f\n" +
            "Previous 5-bar high/low: %.8f / %.8f\n" +
            "Recent 5-bar high/low: %.8f / %.8f\n" +
            "Last 1m OHLC: %.8f / %.8f / %.8f / %.8f\n" +
            "Upper/lower wick: %.8f / %.8f\n" +
            "Travel required incl. buffer: %.8f\n" +
            "Approx. UP/DOWN travel budget: %.8f / %.8f\n" +
            "These are heuristics, NOT validated forecast probabilities.\n",
            buyerPower, sellerPower, buyDollars, sellDollars, recentTrades.size,
            bidDollars, askDollars, flowImbalance * 100, bookImbalance * 100,
            fast1.last(), slow1.last(), fast5.last(), slow5.last(),
            if (fiveDirection == 1) "UP" else if (fiveDirection == -1) "DOWN" else "NEUTRAL",
            fiveScore, oneBull, oneBear, momentumATR, volumeRatio, atr,
            previousHigh, previousLow, recentHigh, recentLow,
            last.open, last.high, last.low, last.close, upperWick, lowerWick,
            needed, estimatedMoveUp, estimatedMoveDown)

        fun done(verdict: String, reason: String) =
            Result(verdict, reason, header + "$verdict: $reason\n" + body,
                buyerPower, sellerPower)

        val candle = replayCandle(one, five, input.spot, input.target, minutesLeft)
        if (candle.direction == 0) return done("WAIT", candle.reason)
        val direction = candle.direction
        if (direction * flowImbalance < 0.08)
            return done("WAIT", "Executed trade flow does not support 5m direction.")
        if (direction * pressure < 0.20)
            return done("WAIT", "Buyer/seller combined power is not strong enough.")
        if (direction * bookImbalance < -0.35)
            return done("WAIT", "Order book strongly opposes the move.")
        return done(if (direction == 1) "BUY UP" else "BUY DOWN",
            "5m trend, 1m setup, volume, executed trade flow and travel budget align.")
    }

    private fun score1m(
        dir: Int, bars: List<Bar>, fast: List<Double>, slow: List<Double>,
        momentum: Double, volume: Double, upStructure: Boolean,
        downStructure: Boolean, upWick: Boolean, downWick: Boolean
    ): Int {
        val last = bars.last()
        return listOf(
            dir * (fast.last() - slow.last()) > 0,
            dir * (fast.last() - fast[fast.lastIndex - 1]) > 0,
            dir * (last.close - fast.last()) > 0,
            dir * momentum > 0.25,
            if (dir == 1) upStructure else downStructure,
            if (dir == 1) upWick else downWick,
            volume >= 1.0 && dir * (last.close - last.open) > 0
        ).count { it }
    }


    /** Candle-only decision used by BOTH live monitoring and historical replay.
     *  Historical order-book/aggTrades snapshots cannot be reconstructed from OHLCV.
     *  Return 0 for WAIT. All inputs must include only candles closed by observation.
     */
    /** BOS = candle CLOSE crossing a previously confirmed 2-left/2-right swing.
     * The break must have occurred within the last 3 closed candles (1m),
     * or last 2 closed candles (5m). This never examines later candles.
     */
    enum class BosMode { NONE, ONE_MINUTE, FIVE_MINUTE, EITHER }

    data class CandleRules(
        val name: String = "Original",
        val minScore: Int = 4,
        val minVolume: Double = 0.65,
        val minMomentum: Double = 0.0,
        val requireStructure: Boolean = false,
        val requireWick: Boolean = false,
        val travelMultiplier: Double = 1.0,
        val bosMode: BosMode = BosMode.NONE
    )

    /** Uses only the candles passed in; recent closes must exceed a prior
     * confirmed swing HIGH (up) or LOW (down). Wick-only breaks don't count.
     */
    fun recentBreakOfStructure(
        bars: List<Bar>, direction: Int, recentCandles: Int = 3
    ): Boolean {
        if (direction !in listOf(-1, 1) || bars.size < 10 || recentCandles < 1) return false
        val firstBreak = max(5, bars.size - recentCandles)
        for (i in firstBreak until bars.size) {
            // Pivots require two completed candles on both sides BEFORE the break.
            var lastPivot: Double? = null
            for (p in max(2, i - 42) until i - 2) {
                val v = if (direction == 1) bars[p].high else bars[p].low
                val confirmed = if (direction == 1)
                    v > bars[p - 1].high && v > bars[p - 2].high &&
                    v > bars[p + 1].high && v > bars[p + 2].high
                else
                    v < bars[p - 1].low && v < bars[p - 2].low &&
                    v < bars[p + 1].low && v < bars[p + 2].low
                if (confirmed) lastPivot = v
            }
            val pivot = lastPivot ?: continue
            if (direction == 1 && bars[i - 1].close <= pivot && bars[i].close > pivot)
                return true
            if (direction == -1 && bars[i - 1].close >= pivot && bars[i].close < pivot)
                return true
        }
        return false
    }
    data class CandleSignal(val direction: Int, val reason: String, val score: Int = 0)

    fun replayCandle(
        minuteBars: List<Bar>, fiveBars: List<Bar>,
        spot: Double, target: Double, minutesLeft: Double,
        rules: CandleRules = CandleRules()
    ): CandleSignal {
        fun wait(reason: String) = CandleSignal(0, reason)
        if (minutesLeft <= 3.0 || minutesLeft > 5.0)
            return wait("Outside the last-five-minute entry window (3 < minutes left <= 5).")
        if (minuteBars.size < 115 || fiveBars.size < 115)
            return wait("Insufficient EMA100 warm-up candles.")
        val one = minuteBars
        val five = fiveBars
        if (!continuous(one.takeLast(110), 60_000L) ||
            !continuous(five.takeLast(110), 300_000L))
            return wait("Missing candle interval.")
        val fast1 = ema(one.map { it.close }, 4)
        val slow1 = ema(one.map { it.close }, 100)
        val fast5 = ema(five.map { it.close }, 4)
        val slow5 = ema(five.map { it.close }, 100)
        val fiveScore = listOf(
            sign(fast5.last() - slow5.last()),
            sign(five.last().close - slow5.last()),
            sign(fast5.last() - fast5[fast5.lastIndex - 1])
        ).sum()
        val dir = if (fiveScore >= 2) 1 else if (fiveScore <= -2) -1 else 0
        if (dir == 0) return wait("5m trend neutral.")
        if (!targetAllows(dir, spot, target))
            return wait("Trend cannot cross target from the required side.")
        val atr = atr14(one)
        if (!atr.isFinite() || atr <= 0) return wait("ATR unavailable.")
        val last = one.last()
        val prev = one.subList(one.size - 11, one.size - 6)
        val recent = one.subList(one.size - 6, one.size - 1)
        val upStructure = recent.maxOf { it.high } > prev.maxOf { it.high } &&
            recent.minOf { it.low } > prev.minOf { it.low }
        val downStructure = recent.maxOf { it.high } < prev.maxOf { it.high } &&
            recent.minOf { it.low } < prev.minOf { it.low }
        val upperWick = last.high - max(last.open, last.close)
        val lowerWick = min(last.open, last.close) - last.low
        val candleRange = max(1.0e-10, last.high - last.low)
        val wickUp = lowerWick / candleRange >= 0.35 && lowerWick > upperWick * 1.5
        val wickDown = upperWick / candleRange >= 0.35 && upperWick > lowerWick * 1.5
        val priorVolume = one.subList(one.size - 21, one.size - 1)
            .map { it.quoteVolume }.average()
        val volumeRatio = if (priorVolume > 0) last.quoteVolume / priorVolume else 0.0
        val delta3 = last.close - one[one.size - 4].close
        val momentumATR = delta3 / atr
        val score = score1m(dir, one, fast1, slow1, momentumATR, volumeRatio,
            upStructure, downStructure, wickUp, wickDown)
        if (score < rules.minScore) return CandleSignal(0, "1m score below threshold.", score)
        if (volumeRatio < rules.minVolume)
            return CandleSignal(0, "Volume below threshold.", score)
        if (dir * momentumATR < rules.minMomentum)
            return CandleSignal(0, "Momentum below threshold.", score)
        if (rules.requireStructure && !(if (dir == 1) upStructure else downStructure))
            return CandleSignal(0, "Structure not confirmed.", score)
        if (rules.requireWick && !(if (dir == 1) wickUp else wickDown))
            return CandleSignal(0, "Wick not confirmed.", score)
        if (rules.bosMode != BosMode.NONE) {
            val bos1m = recentBreakOfStructure(one, dir, 3)
            val bos5m = recentBreakOfStructure(five, dir, 2)
            val bosConfirmed = when (rules.bosMode) {
                BosMode.ONE_MINUTE -> bos1m
                BosMode.FIVE_MINUTE -> bos5m
                BosMode.EITHER -> bos1m || bos5m
                BosMode.NONE -> true
            }
            if (!bosConfirmed) return CandleSignal(0,
                "No confirmed " + rules.bosMode + " break of structure.", score)
        }
        val needed = abs(spot - target) + max(atr * 0.10, spot * 0.00005)
        val projected = travelBudget(atr, minutesLeft, dir * delta3 / 3.0) *
            rules.travelMultiplier
        if (needed > projected)
            return CandleSignal(0, "Target too far to reach in time (ATR heuristic).", score)
        return CandleSignal(dir, "Candle setup passed; live order flow still required.", score)
    }

    private fun sign(x: Double): Int = when {
        x > 0 -> 1
        x < 0 -> -1
        else -> 0
    }
    private fun continuous(bars: List<Bar>, interval: Long): Boolean =
        bars.zipWithNext().all { (a, b) -> b.openedAt - a.openedAt == interval }

    private fun ema(prices: List<Double>, period: Int): List<Double> {
        val alpha = 2.0 / (period + 1.0)
        var current = prices.take(period).average()
        return prices.mapIndexed { i, price ->
            if (i < period) {
                if (i == period - 1) current = prices.take(period).average()
            } else current += alpha * (price - current)
            current
        }
    }

    private fun atr14(bars: List<Bar>): Double {
        val ranges = (bars.size - 14 until bars.size).map { i ->
            val b = bars[i]
            max(b.high - b.low,
                max(abs(b.high - bars[i - 1].close),
                    abs(b.low - bars[i - 1].close)))
        }
        return ranges.average()
    }
}
