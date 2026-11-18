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

    fun report(markets: List<Market>, skipped: Int = 0): String {
        val sorted = markets.sortedBy { it.closeTs }
        val byMarket = sorted.map { it to analyze(it) }
        val holdoutTickers = sorted.drop(sorted.size * 7 / 10).map { it.ticker }.toSet()
        val reaches = byMarket.flatMap { it.second.reaches }
        val pullbacks = byMarket.flatMap { it.second.pullbacks }
        val b = StringBuilder()
        b.appendLine("KALSHI BTC 15-MINUTE HISTORICAL PATTERN TEST")
        b.appendLine("REAL Kalshi settled results + recorded YES bid/ask 1-minute closes")
        b.appendLine("Markets with usable observations: ${sorted.size}; skipped/empty: $skipped")
        if (sorted.isNotEmpty()) {
            b.appendLine("UTC close range: ${java.time.Instant.ofEpochSecond(sorted.first().closeTs)} to ${java.time.Instant.ofEpochSecond(sorted.last().closeTs)}")
        }
        b.appendLine("YES=Kalshi contract YES; NO=Kalshi contract NO. YES/NO winner from settlement.")
        b.appendLine("Signal at >=60/70/80/90 uses YES quote midpoint for YES, complementary midpoint for NO.")
        b.appendLine("Only one-minute CLOSING quotes are observed; shorter spikes and exact crossing order are unknown.")
        b.appendLine("One FIRST reach per side/line/market; repeat crossings counted separately, NOT as independent wins.")
        b.appendLine("Dip means a later observed CLOSE below the same line.")
        b.appendLine("STAY requires every remaining minute to have a valid quote; otherwise UNKNOWN unless a dip was seen.")
        b.appendLine("First three minutes (15..13 left) are research only, NOT suggested trade entries.")
        b.appendLine("Left=minutes to expiry after the observation; 0=settlement, no new entry.")
        b.appendLine("Spread >25 cents or missing best bid/ask is skipped, never fabricated.")
        b.appendLine("Net figures = hypothetical profit per 1 contract at recorded ask less estimated general taker fee.")
        b.appendLine("No fill/slippage guarantee. Different contract fee schedules can change this result.")
        b.appendLine("Holdout = newest 30% of markets; older 70% are discovery/training.")
        b.appendLine("Averages and evidence ranges are not guaranteed predictions. n<30 = SMALL SAMPLE.")
        b.appendLine()

        b.appendLine("1) TOUCH THEN STAY OR DIP: ACTUAL WIN COUNTS")
        for (side in listOf(1, -1)) for (level in LEVELS) {
            val items = reaches.filter { it.side == side && it.level == level }
            val dip = items.filter { it.fellBelow == true }
            val stay = items.filter { it.fellBelow == false }
            val unknown = items.count { it.fellBelow == null }
            val winners = items.filter { it.won }
            val winnersStayed = winners.count { it.fellBelow == false }
            val winnersDipped = winners.count { it.fellBelow == true }
            b.appendLine("Among actual WINNERS of this side/line: stayed $winnersStayed/${winners.size} (${pct(winnersStayed, winners.size)}), dipped $winnersDipped/${winners.size} (${pct(winnersDipped, winners.size)}), unknown ${winners.size - winnersStayed - winnersDipped}")
            b.appendLine("${if (side == 1) "YES" else "NO"} $level% reached=${items.size}, observed crossing events=${items.sumOf { it.touchCount }}, final wins=${items.count { it.won }}/${items.size} (${pct(items.count { it.won }, items.size)}) | stayed=${stay.count { it.won }}/${stay.size} (${pct(stay.count { it.won }, stay.size)}) | dipped=${dip.count { it.won }}/${dip.size} (${pct(dip.count { it.won }, dip.size)}) | unknown=$unknown")
        }
        b.appendLine()
        b.appendLine("2) FIRST REACH BY MINUTE LEFT, BOTH SIDES, ALL LEVELS")
        b.appendLine("side,line,left,markets,wins,win%,dips,stays,unknown,observed_crossings")
        for (side in listOf(1, -1)) for (level in LEVELS) for (left in 15 downTo 0) {
            val x = reaches.filter { it.side == side && it.level == level && it.left == left }
            b.appendLine("${if (side == 1) "YES" else "NO"},$level,$left,${x.size},${x.count { it.won }},${pct(x.count { it.won }, x.size)},${x.count { it.fellBelow == true }},${x.count { it.fellBelow == false }},${x.count { it.fellBelow == null }},${x.sumOf { it.touchCount }}")
        }
        b.appendLine()
        b.appendLine("3) PULLBACKS: FIRST REACHED LINE, THEN RETRACED TO A LOWER LEVEL")
        b.appendLine("These are actual outcomes AFTER the retrace, not the original displayed percentage.")
        b.appendLine("side,from,to,n,wins,win%,holdout_n,holdout_wins,holdout_win%,mean_ask_cents,mean_net_cents_per_contract")
        data class Candidate(val title: String, val holdoutN: Int,
            val holdoutWins: Int, val net: Double)
        val candidates = ArrayList<Candidate>()
        for (side in listOf(1, -1)) for (level in LEVELS) {
            for (to in (level - 10) downTo 10 step 10) {
                val x = pullbacks.filter {
                    it.side == side && it.level == level && it.retrace == to
                }
                val h = x.filter { holdoutTickers.contains(it.ticker) }
                val name = "${if (side == 1) "YES" else "NO"} $level->$to"
                b.appendLine("${if (side == 1) "YES" else "NO"},$level,$to,${x.size},${x.count { it.won }},${pct(x.count { it.won }, x.size)},${h.size},${h.count { it.won }},${pct(h.count { it.won }, h.size)},${if (x.isEmpty()) "n/a" else fmt(100*x.sumOf { it.ask }/x.size)},${net(x.sumOf { it.net }, x.size)}")
                if (h.size >= MIN_SAMPLES) candidates.add(
                    Candidate(name, h.size, h.count { it.won }, h.sumOf { it.net } / h.size))
            }
        }
        b.appendLine()
        b.appendLine("4) PULLBACKS BY EXACT MINUTE LEFT (only observed combinations)")
        b.appendLine("side,from,to,left,n,wins,win%,mean_ask_cents,mean_net_cents_per_contract")
        for (side in listOf(1, -1)) for (level in LEVELS)
            for (to in (level - 10) downTo 10 step 10)
                for (left in 15 downTo 0) {
                    val x = pullbacks.filter { it.side == side &&
                        it.level == level && it.retrace == to && it.left == left }
                    if (x.isEmpty()) continue
                    b.appendLine("${if (side == 1) "YES" else "NO"},$level,$to,$left,${x.size},${x.count { it.won }},${pct(x.count { it.won }, x.size)},${fmt(100*x.sumOf { it.ask }/x.size)},${net(x.sumOf { it.net }, x.size)}")
                }
        b.appendLine()
        b.appendLine("5) PROMISING PULLBACKS IN CHRONOLOGICAL HOLDOUT (n>=30)")
        val promising = candidates.filter { it.net > 0.0 }.sortedByDescending { it.net }
        if (promising.isEmpty()) b.appendLine("NONE validated as positive in a >=30-sample holdout.")
        for (c in promising) b.appendLine("${c.title}: holdout ${c.holdoutWins}/${c.holdoutN} (${pct(c.holdoutWins, c.holdoutN)}), lower Wilson 95% ${fmt(100*wilsonLower(c.holdoutWins, c.holdoutN))}%, mean net ${net(c.net * c.holdoutN, c.holdoutN)}")
        b.appendLine("Many patterns and time buckets are checked. Multiple-testing bias and changing markets can create false discoveries.")
        b.appendLine("Use this to formulate a hypothesis; verify on a later untouched sample before risking money.")
        return KalshiPathExplorer.report(sorted) +
            "\n=== ADDITIONAL FIRST-REACH AND STAY/DIP DIAGNOSTICS ===\n" +
            b.toString()
    }
}
