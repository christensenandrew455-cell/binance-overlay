package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Focused research report. Four requested threshold-crossing sequences ONLY.
 * The 70/60 labels describe crossings, not an assumed fill at 60 cents.
 * Executable price proxy is the observed YES or NO ask on the completion candle.
 */
object KalshiFourPatternReport {
    internal data class Threshold(val atLeast: Boolean, val percent: Int) {
        fun reached(yesMid: Double): Boolean =
            if (atLeast) yesMid + 1e-8 >= percent else yesMid <= percent + 1e-8
    }
    internal data class Rule(
        val label: String, val thresholds: List<Threshold>, val buySide: Int
    )
    internal data class Window(val upper: Int, val lower: Int) {
        fun contains(left: Int) = left in lower..upper
        fun label() = upper.toString() + " to " + lower + " minutes left"
    }
    internal val rules = listOf(
        Rule("YES 70 -> 60 | BUY DOWN",
            listOf(Threshold(true, 70), Threshold(false, 60)), -1),
        Rule("YES 30 -> 40 | BUY UP (mirror)",
            listOf(Threshold(false, 30), Threshold(true, 40)), 1),
        Rule("YES 70 -> 50 -> 60 | BUY UP",
            listOf(Threshold(true, 70), Threshold(false, 50),
                Threshold(true, 60)), 1),
        Rule("YES 30 -> 50 -> 40 | BUY DOWN (mirror)",
            listOf(Threshold(false, 30), Threshold(true, 50),
                Threshold(false, 40)), -1)
    )
    private val whole = Window(12, 1)
    private val windows = listOf(
        whole, Window(12, 9), Window(8, 6), Window(5, 3), Window(2, 1)
    )

    /**
     * Sequential, distinct valid one-minute closes. >= / <= means crossing
     * a threshold, including a price that moves past the named level.
     * Never claim a quote or fill at a level that the minute candle skipped.
     * If completed during research-only minutes, re-arm for another setup.
     */
    internal fun firstCompletion(
        quotes: List<KalshiPatternMath.Quote>, rule: Rule, window: Window = whole
    ): KalshiPatternMath.Quote? {
        var stage = 0
        var previousLeft = 16
        val sorted = quotes.filter { it.valid() }.sortedByDescending { it.left }
            .distinctBy { it.left }
        for (q in sorted) {
            val yes = q.strength(1)
            if (stage == 0) {
                if (rule.thresholds[0].reached(yes)) {
                    stage = 1
                    previousLeft = q.left
                }
                continue
            }
            if (q.left >= previousLeft || !rule.thresholds[stage].reached(yes))
                continue
            previousLeft = q.left
            stage++
            if (stage == rule.thresholds.size) {
                stage = 0
                if (q.left in 1..12 && window.contains(q.left) &&
                    q.entryAsk(rule.buySide) in 0.01..0.99)
                    return q
                // The first completion was outside the requested window.
                // Keep scanning for a fresh, later sequence.
            }
        }
        return null
    }

    internal class Stats {
        var count = 0
        var wins = 0
        private var askSum = 0.0
        private var feeSum = 0.0
        private var netSum = 0.0
        fun add(quote: KalshiPatternMath.Quote, yesWon: Boolean, side: Int) {
            val ask = quote.entryAsk(side)
            if (ask !in 0.01..0.99) return
            val fee = KalshiPatternMath.approximateFee(ask)
            val won = if (side == 1) yesWon else !yesWon
            count++
            if (won) wins++
            askSum += ask
            feeSum += fee
            netSum += (if (won) 1.0 else 0.0) - ask - fee
        }
        fun winPct() = if (count == 0) Double.NaN else wins * 100.0 / count
        fun askCents() = if (count == 0) Double.NaN else askSum * 100.0 / count
        fun feeCents() = if (count == 0) Double.NaN else feeSum * 100.0 / count
        fun edgePp() = winPct() - askCents()
        fun netCentsPerTrade() =
            if (count == 0) Double.NaN else netSum * 100.0 / count
        fun netCentsPerMarket(total: Int) =
            if (total <= 0) Double.NaN else netSum * 100.0 / total
        fun hitPct(total: Int) =
            if (total <= 0) Double.NaN else count * 100.0 / total
    }
    internal data class Score(val older: Stats, val newest: Stats, val all: Stats)
    internal fun qualifies(score: Score, olderTotal: Int, total: Int): Boolean =
        score.older.count >= 30 &&
            score.older.hitPct(olderTotal) + 1e-8 >= 10.0 &&
            score.all.hitPct(total) + 1e-8 >= 10.0 &&
            score.older.edgePp() + 1e-8 >= 20.0 &&
            score.older.netCentsPerTrade() > 0.0

    private fun calculate(
        markets: List<KalshiPatternMath.Market>,
        rule: Rule, window: Window, olderTotal: Int
    ): Score {
        val older = Stats()
        val newest = Stats()
        val all = Stats()
        markets.forEachIndexed { index, market ->
            val q = firstCompletion(market.quotes, rule, window) ?: return@forEachIndexed
            all.add(q, market.yesWon, rule.buySide)
            if (index < olderTotal) older.add(q, market.yesWon, rule.buySide)
            else newest.add(q, market.yesWon, rule.buySide)
        }
        return Score(older, newest, all)
    }

    private fun num(x: Double): String =
        if (x.isNaN()) "n/a" else String.format(Locale.US, "%.1f", x)
    private fun signed(x: Double): String =
        if (x.isNaN()) "n/a" else String.format(Locale.US, "%+.1f", x)
    private fun detail(s: Stats, total: Int): String =
        s.count.toString() + "/" + total +
            " | hit=" + num(s.hitPct(total)) + "%" +
            " | wins=" + s.wins + "/" + s.count +
            " | WR=" + num(s.winPct()) + "%" +
            " | buy ask=" + num(s.askCents()) + "c" +
            " | fee=" + num(s.feeCents()) + "c" +
            " | edge=" + signed(s.edgePp()) + "pp" +
            " | net=" + signed(s.netCentsPerTrade()) + "c/trade"

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No settled markets with valid quotes."
        val ordered = markets.distinctBy { it.ticker }
            .sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }
                .thenBy { it.ticker })
        val n = ordered.size
        val olderN = (n * 7 / 10).coerceIn(1, n)
        val newestN = n - olderN
        val eastern = ZoneId.of("America/New_York")
        val first = Instant.ofEpochSecond(ordered.first().closeTs)
            .atZone(eastern).toLocalDate()
        val last = Instant.ofEpochSecond(ordered.last().closeTs)
            .atZone(eastern).toLocalDate()
        val out = StringBuilder()
        out.appendLine("=== BTC KALSHI: THE FOUR REQUESTED PATTERNS ===")
        out.appendLine("Distinct settled markets: " + n + " | skipped: " + skipped)
        if (n != 1000) out.appendLine("INCOMPLETE: expected 1000 unique settled markets.")
        out.appendLine("Dates (America/New_York): " + first + " through " + last)
        out.appendLine("Oldest discovery: " + olderN + " | newest check: " + newestN)
        out.appendLine("All four ALWAYS print, whether they pass a screen or not.")
        out.appendLine("YES midpoint stages use observed 1-minute >= / <= crossings on DIFFERENT closes.")
        out.appendLine("Skipped thresholds are allowed, but entry is at ACTUAL observed BUY-side ask, NOT at the named percentage.")
        out.appendLine("15-13 minutes left are research-only. Trades counted at 12-1 minutes left.")
        out.appendLine("Hits = distinct markets with the path; WR = that BUY side winning settlement.")
        out.appendLine("A 10.0% hit rate is INCLUDED. +20pp compares settlement WR to mean BUY-side ask.")
        out.appendLine("Costs include estimated taker fees; fills are NOT guaranteed.")
        out.appendLine("A +20pp result still needs validation on the newest markets.")
        out.appendLine()
        for ((index, rule) in rules.withIndex()) {
            val scores = windows.associateWith { calculate(ordered, rule, it, olderN) }
            val full = scores.getValue(whole)
            out.appendLine((index + 1).toString() + ") " + rule.label)
            out.appendLine("   Full timeframe: " + whole.label())
            out.appendLine("   All:    " + detail(full.all, n))
            out.appendLine("   Older:  " + detail(full.older, olderN))
            out.appendLine("   Newest: " + detail(full.newest, newestN))
            out.appendLine("   20pp + 10% research screen: " +
                (if (qualifies(full, olderN, n)) "PASS (not proof of profit)"
                 else "FAIL (still reported; check ask, spread, and frequency)"))
            val timed = windows.drop(1).map { it to scores.getValue(it) }
            val credible = timed.filter { it.second.older.count >= 10 }
            val best = credible.maxWithOrNull(
                compareBy<Pair<Window, Score>> {
                    it.second.older.netCentsPerMarket(olderN)
                }.thenBy { it.second.older.count }
            )
            if (best == null) out.appendLine(
                "   Best narrower timeframe: insufficient older samples (under 10 per window)")
            else {
                out.appendLine("   Best exploratory narrower timeframe: " + best.first.label() +
                    " (chosen on oldest markets only)")
                out.appendLine("      Older: " + detail(best.second.older, olderN))
                out.appendLine("      Newest: " + detail(best.second.newest, newestN))
            }
            out.appendLine("   Each time block (non-overlapping entry minutes):")
            for ((window, stats) in timed) {
                out.appendLine("      " + window.label() + ": " +
                    detail(stats.all, n))
            }
            out.appendLine()
        }
        out.appendLine("Rules are tested separately; market counts can overlap across rules.")
        out.appendLine("The mirror of 70->60 DOWN is 30->40 UP; the mirror of 70->50->60 UP is 30->50->40 DOWN.")
        out.appendLine("No hidden eligibility filter removes any of these four results.")
        out.appendLine("This is research only, not an automatic trade signal.")
        return out.toString()
    }
}
