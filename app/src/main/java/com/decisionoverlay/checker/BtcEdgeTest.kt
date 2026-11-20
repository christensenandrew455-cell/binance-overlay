package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Indicator vs. chart-only research. This never places trades or modifies
 * the live chart. Every time frame is assembled from the same Binance.US
 * 1-minute tape, using CLOSED candles only.
 */
internal object BtcEdgeTest {
    private const val MINUTE_MS = 60_000L
    private const val HISTORY = 22
    private val TIMEFRAMES = listOf(1, 5, 15)
    // Four preselected, non-overlapping decision moments per BTC market.
    private val CHECKPOINTS = listOf(11, 8, 5, 2)
    internal val INDICATORS = listOf(
        "RSI14", "SMA9/20", "EMA9/21", "MACD5/13/4",
        "Bollinger20", "Stoch14/3", "ADX10-DMI", "ATR14 expansion",
        "CCI14", "ROC10", "OBV10", "MFI14", "VWAP20", "RVOL20",
        "Williams%R14", "CMF20", "Aroon14"
    )

    data class Signal(val timeframe: Int, val indicator: String, val state: Int)
    data class Row(val band: Int, val left: Int, val signals: List<Signal>)
    data class Market(val at: Long, val up: Boolean, val rows: List<Row>)

    // Each observation's band is the EXISTING chart's custom distance
    // indicator, NOT a % BTC price return or a calibrated probability.
    internal fun chartBand(level: Double): Int {
        if (!level.isFinite()) return 0
        val magnitude = (abs(level) / 10).toInt() * 10
        return if (magnitude < 60) 50 else if (level < 0) -magnitude else magnitude
    }

    /**
     * lastCloseExclusive is the instant at which the decision is made.
     * A 5m/15m candle is not visible until its entire interval has ended.
     */
    internal fun completedBars(
        tape: Map<Long, BtcAcmMath.Candle>, lastCloseExclusive: Long,
        timeframe: Int, count: Int = HISTORY
    ): List<BtcAcmMath.Candle>? {
        require(timeframe in TIMEFRAMES && count > 0)
        val interval = timeframe * MINUTE_MS
        val lastBoundary = Math.floorDiv(lastCloseExclusive, interval) * interval
        val result = ArrayList<BtcAcmMath.Candle>(count)
        for (index in count downTo 1) {
            val start = lastBoundary - index * interval
            var first: BtcAcmMath.Candle? = null
            var last: BtcAcmMath.Candle? = null
            var high = Double.NEGATIVE_INFINITY
            var low = Double.POSITIVE_INFINITY
            var volume = 0.0
            for (minute in 0 until timeframe) {
                val candle = tape[start + minute * MINUTE_MS] ?: return null
                if (!candle.valid() || !candle.volume.isFinite() || candle.volume < 0.0)
                    return null
                if (first == null) first = candle
                last = candle
                high = max(high, candle.high)
                low = min(low, candle.low)
                volume += candle.volume
            }
            result.add(BtcAcmMath.Candle(
                start, first!!.open, high, low, last!!.close, volume
            ))
        }
        return result
    }

    fun market(
        start: Long, target: Double, up: Boolean,
        bars: Map<Long, BtcAcmMath.Candle>, levels: List<Double>
    ): Market? {
        if (target <= 0.0 || levels.size < 14) return null
        val rows = ArrayList<Row>(14)
        for (minute in 1..14) {
            val left = 15 - minute
            val end = start + minute * MINUTE_MS
            val signals = ArrayList<Signal>(INDICATORS.size * TIMEFRAMES.size)
            for (tf in TIMEFRAMES) {
                val completed = completedBars(bars, end, tf) ?: continue
                val values = indicators(completed)
                for ((name, state) in values) if (state != 0)
                    signals.add(Signal(tf, name, state))
            }
            if (signals.isNotEmpty()) rows.add(Row(chartBand(levels[minute - 1]), left, signals))
        }
        return if (CHECKPOINTS.all { checkpoint -> rows.any { it.left == checkpoint } })
            Market(start, up, rows) else null
    }

    private fun side(value: Double, epsilon: Double = 1e-10): Int = when {
        !value.isFinite() || abs(value) <= epsilon -> 0
        value > 0.0 -> 1
        else -> -1
    }

    private fun ema(values: List<Double>, period: Int): List<Double> {
        val alpha = 2.0 / (period + 1)
        var current = values.first()
        return values.map { value ->
            current += alpha * (value - current)
            current
        }
    }

    /**
     * Returns sign-coded states, not buy/sell predictions. A state of -1
     * can still historically predict UP; that relationship is fitted only
     * on the older training markets.
     */
    internal fun indicators(candles: List<BtcAcmMath.Candle>): Map<String, Int> {
        if (candles.size < HISTORY || candles.any {
            !it.valid() || !it.volume.isFinite() || it.volume < 0.0
        }) return emptyMap()
        val bars = candles.takeLast(HISTORY)
        val close = bars.map { it.close }
        val typical = bars.map { (it.high + it.low + it.close) / 3.0 }
        val last = bars.last()
        val changes = (1 until bars.size).map { close[it] - close[it - 1] }
        val up14 = changes.takeLast(14).sumOf { max(0.0, it) }
        val down14 = changes.takeLast(14).sumOf { max(0.0, -it) }
        val rsi = if (up14 + down14 == 0.0) 50.0
            else 100.0 * up14 / (up14 + down14)
        val sma9 = close.takeLast(9).average()
        val sma20 = close.takeLast(20).average()
        val ema9 = ema(close, 9).last()
        val ema21 = ema(close, 21).last()

        val fast = ema(close, 5)
        val slow = ema(close, 13)
        val macd = fast.zip(slow) { a, b -> a - b }
        val histogram = macd.last() - ema(macd, 4).last()

        val std20 = sqrt(close.takeLast(20).sumOf { (it - sma20) * (it - sma20) } / 20.0)
        val bollinger = if (std20 > 0.0) (last.close - sma20) / (2.0 * std20) else 0.0
        fun stoch(endExclusive: Int): Double {
            val window = bars.subList(endExclusive - 14, endExclusive)
            val high = window.maxOf { it.high }
            val low = window.minOf { it.low }
            return if (high > low) 100.0 * (bars[endExclusive - 1].close - low) / (high - low)
                else 50.0
        }
        val k = stoch(bars.size)
        val d = (0..2).map { stoch(bars.size - it) }.average()

        // DMI10 plus a ten-point mean of DX10; uses past bars only.
        val plus = DoubleArray(bars.size)
        val minus = DoubleArray(bars.size)
        val tr = DoubleArray(bars.size)
        for (i in 1 until bars.size) {
            val upMove = bars[i].high - bars[i - 1].high
            val downMove = bars[i - 1].low - bars[i].low
            plus[i] = if (upMove > downMove && upMove > 0.0) upMove else 0.0
            minus[i] = if (downMove > upMove && downMove > 0.0) downMove else 0.0
            tr[i] = max(bars[i].high - bars[i].low,
                max(abs(bars[i].high - bars[i - 1].close),
                    abs(bars[i].low - bars[i - 1].close)))
        }
        fun total(a: DoubleArray, end: Int, n: Int) = ((end - n + 1)..end).sumOf { a[it] }
        val dx = (12..21).map { i ->
            val p = total(plus, i, 10)
            val m = total(minus, i, 10)
            if (p + m <= 0.0) 0.0 else 100 * abs(p - m) / (p + m)
        }.average()
        val dmi = if (dx >= 20.0) side(total(plus, 21, 10) - total(minus, 21, 10))
            else 0
        val atrNow = tr.slice(8..21).average()
        val atrEarlier = tr.slice(5..18).average()

        val meanTypical = typical.takeLast(14).average()
        val deviation = typical.takeLast(14).sumOf { abs(it - meanTypical) } / 14.0
        val cci = if (deviation > 0.0) (typical.last() - meanTypical) /
            (0.015 * deviation) else 0.0
        val roc = last.close / bars[bars.size - 11].close - 1.0
        val obv = (bars.size - 10 until bars.size).sumOf { i ->
            side(close[i] - close[i - 1]).toDouble() * bars[i].volume
        }
        var positiveFlow = 0.0
        var negativeFlow = 0.0
        for (i in bars.size - 14 until bars.size) {
            val amount = typical[i] * bars[i].volume
            if (typical[i] > typical[i - 1]) positiveFlow += amount
            if (typical[i] < typical[i - 1]) negativeFlow += amount
        }
        val mfi = if (positiveFlow + negativeFlow == 0.0) 50.0
            else 100.0 * positiveFlow / (positiveFlow + negativeFlow)

        val last20 = bars.takeLast(20)
        val totalVolume = last20.sumOf { it.volume }
        val vwap = if (totalVolume > 0.0) last20.sumOf {
            ((it.high + it.low + it.close) / 3.0) * it.volume
        } / totalVolume else last.close
        val avgPreviousVolume = bars.dropLast(1).takeLast(20).map { it.volume }.average()
        val rvol = if (avgPreviousVolume > 0.0) last.volume / avgPreviousVolume - 1.0 else 0.0
        val highs14 = bars.takeLast(14).maxOf { it.high }
        val lows14 = bars.takeLast(14).minOf { it.low }
        val williams = if (highs14 > lows14)
            100 * (last.close - lows14) / (highs14 - lows14) - 50.0 else 0.0
        val cmf = if (totalVolume > 0.0) last20.sumOf {
            if (it.high > it.low)
                ((2 * it.close - it.high - it.low) / (it.high - it.low)) * it.volume
            else 0.0
        } / totalVolume else 0.0

        val window14 = bars.takeLast(14)
        val mostRecentHigh = window14.indexOfLast { it.high == highs14 }
        val mostRecentLow = window14.indexOfLast { it.low == lows14 }
        val aroon = (mostRecentHigh - mostRecentLow).toDouble()

        return linkedMapOf(
            "RSI14" to side(rsi - 50.0),
            "SMA9/20" to side(sma9 - sma20),
            "EMA9/21" to side(ema9 - ema21),
            "MACD5/13/4" to side(histogram),
            "Bollinger20" to side(bollinger),
            "Stoch14/3" to side(k - d),
            "ADX10-DMI" to dmi,
            "ATR14 expansion" to side(atrNow - atrEarlier),
            "CCI14" to side(cci),
            "ROC10" to side(roc),
            "OBV10" to side(obv),
            "MFI14" to side(mfi - 50.0),
            "VWAP20" to side(last.close - vwap),
            "RVOL20" to side(rvol),
            "Williams%R14" to side(williams),
            "CMF20" to side(cmf),
            "Aroon14" to side(aroon)
        )
    }

    private data class Counts(var hits: Int = 0, var up: Int = 0) {
        fun add(outcome: Boolean) {
            hits++
            if (outcome) up++
        }
        fun direction(): Boolean? = when {
            hits == 0 || up * 2 == hits -> null
            else -> up * 2 > hits
        }
    }

    private data class ChartKey(val band: Int, val left: Int)
    private data class Setup(
        val indicator: String, val timeframe: Int, val left: Int, val state: Int
    )
    private data class Evaluation(
        val key: Setup, val train: Counts,
        var hits: Int = 0, var indicatorWins: Int = 0,
        var chartWins: Int = 0, var disagreements: Int = 0,
        var disagreementWins: Int = 0
    ) {
        val indicatorPct get() = if (hits == 0) 0.0 else 100.0 * indicatorWins / hits
        val chartPct get() = if (hits == 0) 0.0 else 100.0 * chartWins / hits
        val lift get() = indicatorPct - chartPct
    }

    private fun snapshotRows(markets: List<Market>) = markets.flatMap { market ->
        market.rows.filter { it.left in CHECKPOINTS }.map { market to it }
    }
    private fun pct(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun describe(state: Int) = if (state > 0) "positive" else "negative"

    /**
     * Strict chronological split: older 700 fit the chart and indicator
     * directions, newest 300 score them. Test outcomes never fit a direction.
     * Holdout leaderboard is still exploratory due to selecting its winners.
     */
    fun report(markets: List<Market>): String {
        require(markets.size == 1000 && markets.map { it.at }.distinct().size == 1000) {
            "A complete, distinct 1,000-market test is required."
        }
        val ordered = markets.sortedBy { it.at }
        val training = snapshotRows(ordered.take(700))
        val holdout = snapshotRows(ordered.drop(700))
        val charts = mutableMapOf<ChartKey, Counts>()
        val setups = mutableMapOf<Setup, Counts>()
        for ((market, row) in training) {
            charts.getOrPut(ChartKey(row.band, row.left)) { Counts() }.add(market.up)
            for (signal in row.signals.distinct()) {
                val key = Setup(signal.indicator, signal.timeframe, row.left, signal.state)
                setups.getOrPut(key) { Counts() }.add(market.up)
            }
        }
        val evaluations = mutableMapOf<Setup, Evaluation>()
        var chartCalls = 0
        var chartWins = 0
        for ((market, row) in holdout) {
            val baseline = charts[ChartKey(row.band, row.left)]?.direction()
            if (baseline != null) {
                chartCalls++
                if (baseline == market.up) chartWins++
            }
            for (signal in row.signals.distinct()) {
                val key = Setup(signal.indicator, signal.timeframe, row.left, signal.state)
                val fit = setups[key] ?: continue
                if (fit.hits < 40) continue
                val prediction = fit.direction() ?: continue
                if (baseline == null) continue
                val e = evaluations.getOrPut(key) { Evaluation(key, fit) }
                e.hits++
                if (prediction == market.up) e.indicatorWins++
                if (baseline == market.up) e.chartWins++
                if (baseline != prediction) {
                    e.disagreements++
                    if (prediction == market.up) e.disagreementWins++
                }
            }
        }
        val qualified = evaluations.values.filter { it.hits >= 20 }
            .sortedWith(compareByDescending<Evaluation> { it.lift }
                .thenByDescending { it.disagreements }
                .thenByDescending { it.hits }
                .thenBy { it.key.indicator }.thenBy { it.key.timeframe })

        return buildString {
            appendLine("=== 1,000 BTC MARKETS: INDICATORS VS EXISTING CHART ===")
            appendLine("Historical test only. No orders, no changes to the live graph or market scanner.")
            appendLine("1m source candles; 1m/5m/15m indicators use only fully CLOSED candles.")
            appendLine("17 indicator types across all 3 candle timeframes = 51 indicator/timeframe families.")
            appendLine("Decision times fixed BEFORE testing: 11, 8, 5, 2 minutes until 15m close.")
            appendLine("Oldest 700 markets train direction; newest 300 are chronological holdout.")
            appendLine("The chart is the existing custom signed distance-to-BTC-target index, NOT percent BTC change or true win odds.")
            appendLine("Chart-only baseline learns UP/DOWN separately for each chart band and decision minute on training data.")
            appendLine("Indicator state is positive/negative, NOT automatically a buy or sell.")
            appendLine("Each holdout comparison uses the SAME market/minute with the same trained chart baseline.")
            appendLine("A qualified setup requires >=40 training and >=20 holdout occurrences.")
            appendLine("Rankings use observed holdout lift and are exploratory, NOT an untouched post-selection validation.")
            appendLine()
            appendLine("CHART ONLY: " + chartWins + "/" + chartCalls + " correct (" +
                if (chartCalls == 0) "n/a" else pct(100.0 * chartWins / chartCalls) + "%)")
            appendLine("Distinct markets: 700 training / 300 holdout. Four fixed evaluation points per market.")
            appendLine()
            appendLine("=== TOP 30: INDICATOR IMPROVEMENT OVER CHART ON MATCHED HOLDOUT CASES ===")
            if (qualified.isEmpty()) appendLine("No setups meet both minimum sample thresholds.")
            for ((index, e) in qualified.take(30).withIndex()) {
                val direction = setups.getValue(e.key).direction()
                appendLine((index + 1).toString() + ". " + e.key.indicator +
                    " / " + e.key.timeframe + "m / " + e.key.left + " min left / " +
                    describe(e.key.state) + " -> predict " +
                    (if (direction == true) "UP" else "DOWN"))
                appendLine("   train " + e.train.hits + "/700, holdout " + e.hits +
                    "/300; indicator " + e.indicatorWins + "/" + e.hits +
                    " (" + pct(e.indicatorPct) + "%), SAME-CASE chart " +
                    e.chartWins + "/" + e.hits + " (" + pct(e.chartPct) +
                    "%), lift " + (if (e.lift >= 0) "+" else "") + pct(e.lift) + " pp")
                appendLine("   Disagreed with chart " + e.disagreements +
                    " times; indicator correct on " + e.disagreementWins +
                    "/" + e.disagreements + " disagreements.")
            }
            appendLine()
            appendLine("=== ALL INDICATORS: BEST OBSERVED CHECKPOINT PER TIMEFRAME ===")
            appendLine("indicator,timeframe,best_min_left,state,train_hits,holdout_hits,indicator_correct_pct,chart_correct_pct,lift_pp,disagreements")
            for (name in INDICATORS) for (tf in TIMEFRAMES) {
                val best = qualified.filter { it.key.indicator == name && it.key.timeframe == tf }
                    .firstOrNull()
                if (best == null) {
                    appendLine(name + "," + tf + "m,insufficient,0,0,0,n/a,n/a,n/a,0")
                } else appendLine(listOf(
                    name, tf.toString() + "m", best.key.left,
                    describe(best.key.state), best.train.hits, best.hits,
                    pct(best.indicatorPct), pct(best.chartPct),
                    pct(best.lift), best.disagreements
                ).joinToString(","))
            }
            appendLine()
            appendLine("IMPORTANT: Indicators can be correlated with one another; rows are NOT independent trades.")
            appendLine("Tested setups were compared/ranked using the holdout, so run a later untouched forward sample before trusting a winner.")
            appendLine("Returns, fills, fees, slippage and calibration are NOT measured; a higher hit rate is not established profit.")
        }
    }
}
