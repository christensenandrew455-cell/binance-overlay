package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Historical Kalshi YES/NO outcome research. Uses observed one-minute YES
 * bid/ask closes, NOT the custom distance-to-target lines shown on the chart.
 * Times are whole minutes left at the END of the observed 1-minute candle.
 * Every market is counted at most once for a given side/level/retrace.
 */
object KalshiPatternMath {
    val LEVELS = listOf(60, 70, 80, 90)
    const val QUARTER_SECONDS = 15 * 60L
    const val MIN_SAMPLES = 30

    data class Quote(val left: Int, val bid: Double, val ask: Double) {
        fun valid(): Boolean = left in 1..15 &&
            bid.isFinite() && ask.isFinite() &&
            bid >= 0.0 && ask <= 1.0 && bid <= ask &&
            ask - bid <= 0.25
        fun strength(side: Int) = 100.0 * if (side == 1) (bid + ask) / 2.0
            else (1.0 - (bid + ask) / 2.0)
        fun entryAsk(side: Int) = if (side == 1) ask else 1.0 - bid
    }

    data class Market(val ticker: String, val closeTs: Long,
        val yesWon: Boolean, val quotes: List<Quote>)

    data class Reach(val ticker: String, val side: Int, val level: Int,
        val left: Int, val won: Boolean, val fellBelow: Boolean?,
        val touchCount: Int)

    data class Pullback(val ticker: String, val side: Int, val level: Int,
        val retrace: Int, val firstLeft: Int, val left: Int,
        val won: Boolean, val ask: Double, val fee: Double) {
        val net: Double get() = (if (won) 1.0 else 0.0) - ask - fee
    }

    data class Events(val reaches: List<Reach>, val pullbacks: List<Pullback>)

    /** General one-contract taker-fee approximation; not a guaranteed fill. */
    fun approximateFee(ask: Double): Double =
        ceil(7.0 * ask * (1.0 - ask) - 1e-9) / 100.0

    fun analyze(market: Market): Events {
        val quotes = market.quotes.filter { it.valid() }
            .sortedByDescending { it.left }.distinctBy { it.left }
        val all = quotes.associateBy { it.left }
        val reaches = ArrayList<Reach>()
        val pullbacks = ArrayList<Pullback>()
        for (side in listOf(1, -1)) {
            val won = if (side == 1) market.yesWon else !market.yesWon
            for (level in LEVELS) {
                val index = quotes.indexOfFirst { it.strength(side) + 1e-8 >= level }
                if (index < 0) continue
                val first = quotes[index]
                val future = quotes.drop(index + 1)
                val observedDip = future.any { it.strength(side) + 1e-8 < level }
                // An observed dip is certain. With no dip, a missing quote
                // prevents us from falsely claiming the price always stayed up.
                val complete = (1 until first.left).all { all.containsKey(it) }
                val fell: Boolean? = when {
                    observedDip -> true
                    complete -> false
                    else -> null
                }
                // One touch at first observation >= line, then an additional touch
                // each time subsequent minute closes cross up from below.
                // These are NOT independent markets or independent outcomes.
                val touchCount = quotes.indices.count { idx ->
                    quotes[idx].strength(side) >= level - 1e-8 &&
                        (idx == 0 || quotes[idx - 1].strength(side) < level - 1e-8)
                }
                reaches.add(Reach(market.ticker, side, level, first.left, won, fell,
                    touchCount))
                for (retrace in (level - 10) downTo 10 step 10) {
                    val at = future.firstOrNull {
                        it.strength(side) <= retrace + 1e-8
                    } ?: continue
                    val ask = at.entryAsk(side)
                    if (ask !in 0.01..0.99) continue
                    pullbacks.add(Pullback(market.ticker, side, level, retrace,
                        first.left, at.left, won, ask, approximateFee(ask)))
                }
            }
        }
        return Events(reaches, pullbacks)
    }

    private fun fmt(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun pct(wins: Int, count: Int) =
        if (count == 0) "n/a" else fmt(100.0 * wins / count) + "%"
    private fun net(v: Double, n: Int) =
        if (n == 0) "n/a" else String.format(Locale.US, "%+.2fc", 100 * v / n)

    /** Wilson 95% interval, for transparency about small samples. */
    fun wilsonLower(wins: Int, count: Int): Double {
        if (count == 0) return 0.0
        val p = wins.toDouble() / count
        val z = 1.96
        val denom = 1 + z * z / count
        return (p + z * z / (2 * count) -
            z * sqrt((p * (1 - p) + z * z / (4 * count)) / count)) / denom
    }

    /** Compact user-facing output; detailed path logic remains available separately. */
    fun report(markets: List<Market>, skipped: Int = 0): String =
        KalshiCompactReport.report(markets, skipped)

}
