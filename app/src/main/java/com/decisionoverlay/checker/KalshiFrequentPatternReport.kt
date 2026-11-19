package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

/**
 * Small, actionable research shortlist from the EXISTING 1,000-market downloader.
 *
 * These are observed Kalshi YES bid/ask midpoints, not the chart's synthetic
 * BTC-distance percentages. A NO 80->60 path is a YES 20->40 path, not an
 * additional independent market. Outcomes always come from settlement.
 */
object KalshiFrequentPatternReport {
    private const val FIRST_MINUTE_LEFT = 8
    private const val LAST_MINUTE_LEFT = 4
    private const val MIN_ABSOLUTE_MATCHES = 100
    private const val MIN_MARKET_FRACTION = 0.10
    private val levels = (20..90 step 10).toList()

    internal data class PairPath(val first: Int, val second: Int) {
        fun label() = "YES " + first + "->" + second +
            " / NO " + (100 - first) + "->" + (100 - second)
    }

    private val paths = levels.flatMap { a ->
        levels.filter { it != a }.map { b -> PairPath(a, b) }
    }

    /**
     * A first level ARMS a pattern. A subsequent, different minute crossing
     * the second level completes it. Completion outside 8..4 disarms the
     * pattern; another first-level observation is needed before a later signal.
     * Only the earliest completion WITHIN 8..4 is returned.
     *
     * The caller supplies valid quotes ordered from 15 down to 1 minutes left.
     * A quote sitting beyond the final line is not repeatedly called a new hit.
     */
    internal fun firstWindowCompletion(
        quotes: List<KalshiPatternMath.Quote>, path: PairPath
    ): KalshiPatternMath.Quote? {
        var armed = false
        var firstObservedLeft = 16
        val falling = path.second < path.first
        for (quote in quotes) {
            val mid = quote.strength(1)
            val atFirst = if (falling) mid >= path.first - 1e-7
                          else mid <= path.first + 1e-7
            val atSecond = if (falling) mid <= path.second + 1e-7
                           else mid >= path.second - 1e-7
            if (armed && atSecond) {
                val distinctLaterMinute = quote.left < firstObservedLeft
                armed = false
                if (distinctLaterMinute &&
                    quote.left in LAST_MINUTE_LEFT..FIRST_MINUTE_LEFT)
                    return quote
            }
            // The two levels are different. Observing the first level must
            // precede observing the second level on a strictly later candle.
            if (atFirst) {
                armed = true
                firstObservedLeft = quote.left
            }
        }
        return null
    }

    internal class Stats {
        var count = 0
        var yesWins = 0
        private var yesAsk = 0.0
        private var noAsk = 0.0
        private var yesFees = 0.0
        private var noFees = 0.0

        fun add(q: KalshiPatternMath.Quote, yesWon: Boolean) {
            count++
            if (yesWon) yesWins++
            val yes = q.entryAsk(1)
            val no = q.entryAsk(-1)
            yesAsk += yes
            noAsk += no
            yesFees += KalshiPatternMath.approximateFee(yes)
            noFees += KalshiPatternMath.approximateFee(no)
        }

        fun merge(other: Stats) {
            count += other.count
            yesWins += other.yesWins
            yesAsk += other.yesAsk
            noAsk += other.noAsk
            yesFees += other.yesFees
            noFees += other.noFees
        }

        fun wins(side: Int) = if (side == 1) yesWins else count - yesWins
        fun winPct(side: Int) = if (count == 0) 0.0 else 100.0 * wins(side) / count
        fun askCents(side: Int) = if (count == 0) 0.0 else
            100.0 * (if (side == 1) yesAsk else noAsk) / count
        fun feeCents(side: Int) = if (count == 0) 0.0 else
            100.0 * (if (side == 1) yesFees else noFees) / count
        fun costCents(side: Int) = askCents(side) + feeCents(side)
        fun totalNetCents(side: Int) = 100.0 * wins(side) -
            100.0 * (if (side == 1) yesAsk + yesFees else noAsk + noFees)
        fun netCents(side: Int) = if (count == 0) 0.0 else totalNetCents(side) / count
        fun lowerWinPct(side: Int) =
            100.0 * KalshiPatternMath.wilsonLower(wins(side), count)
    }

    private class PatternStats(val path: PairPath) {
        val all = Stats()
        val older = Stats()
        val newer = Stats()
        val byMinute = Array(16) { Stats() }
        val oldMinute = Array(16) { Stats() }
        val newMinute = Array(16) { Stats() }
        val marketIndexes = HashSet<Int>()

        fun add(index: Int, quote: KalshiPatternMath.Quote,
                yesWon: Boolean, isNew: Boolean) {
            if (!marketIndexes.add(index)) return
            all.add(quote, yesWon)
            byMinute[quote.left].add(quote, yesWon)
            if (isNew) {
                newer.add(quote, yesWon)
                newMinute[quote.left].add(quote, yesWon)
            } else {
                older.add(quote, yesWon)
                oldMinute[quote.left].add(quote, yesWon)
            }
        }

        fun between(low: Int, high: Int, buckets: Array<Stats>): Stats {
            val output = Stats()
            for (m in low..high) output.merge(buckets[m])
            return output
        }
    }

    private data class Candidate(val p: PatternStats, val side: Int) {
        val totalNet get() = p.all.totalNetCents(side)
    }

    private fun qualifies(p: PatternStats, side: Int): Boolean {
        val s = p.all
        return p.older.count >= 20 && p.newer.count >= 10 &&
            s.netCents(side) > 0.0 &&
            p.older.netCents(side) > 0.0 &&
            p.newer.netCents(side) > 0.0 &&
            s.lowerWinPct(side) > s.costCents(side)
    }

    private fun num(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun signed(x: Double) = String.format(Locale.US, "%+.1f", x)
    private fun money(x: Double) = String.format(Locale.US, "%+.2f", x)
    private fun percent(n: Int, total: Int) =
        if (total == 0) "n/a" else num(100.0 * n / total) + "%"

    /** Historical hypothetical one-contract entries, NOT evidence of fills. */
    private fun details(s: Stats, side: Int): String =
        "win=" + s.wins(side) + "/" + s.count + " (" + num(s.winPct(side)) +
        "%) | ask=" + num(s.askCents(side)) + "c | fee~" +
        num(s.feeCents(side)) + "c | net=" + signed(s.netCents(side)) + "c/entry"

    /**
     * Pick the two-minute window on the older sample, reporting the newer
     * sample honestly even if profit is negative. This is descriptive research,
     * not an additional pass/fail screen that hides a frequent pattern.
     */
    private fun bestTiming(p: PatternStats, side: Int): String {
        data class ShortWindow(val low: Int, val high: Int, val old: Stats,
                               val recent: Stats, val combined: Stats)
        val options = (4..7).map { low ->
            ShortWindow(low, low + 1,
                p.between(low, low + 1, p.oldMinute),
                p.between(low, low + 1, p.newMinute),
                p.between(low, low + 1, p.byMinute))
        }
        val selected = options.filter { it.old.count > 0 }
            .maxWithOrNull(compareBy<ShortWindow> { it.old.totalNetCents(side) }
                .thenBy { it.old.count })
            ?: return "No two-minute timing observations in older data."
        return "Most promising historical 2-minute range=" +
            selected.high + "-" + selected.low +
            " min left | hit=" + selected.combined.count +
            " | older n=" + selected.old.count + " net=" +
            signed(selected.old.netCents(side)) + "c/entry" +
            " | newest n=" + selected.recent.count + " net=" +
            (if (selected.recent.count == 0) "n/a"
             else signed(selected.recent.netCents(side)) + "c/entry") +
            " (selected using older data, not independently validated)"
    }

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No settled BTC markets available for analysis."
        val ordered = markets.distinctBy { it.ticker }
            .sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }
                .thenBy { it.ticker })
        val split = ordered.size * 7 / 10
        val minimum = max(MIN_ABSOLUTE_MATCHES,
            ceil(ordered.size * MIN_MARKET_FRACTION).toInt())
        val stats = paths.associateWith { PatternStats(it) }

        var validMinuteCloses = 0
        var noFullWindow = 0
        for ((index, market) in ordered.withIndex()) {
            val quotes = market.quotes.filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }
            validMinuteCloses += quotes.size
            if ((LAST_MINUTE_LEFT..FIRST_MINUTE_LEFT).any { m ->
                    quotes.none { it.left == m }
                }) noFullWindow++

            if (quotes.size < 2) continue
            for (path in paths) {
                val entry = firstWindowCompletion(quotes, path) ?: continue
                // A midpoint alone is not an executable price. Require usable
                // historical asks on both sides to compare recommendations.
                if (entry.entryAsk(1) !in 0.01..0.99 ||
                    entry.entryAsk(-1) !in 0.01..0.99) continue
                stats.getValue(path).add(index, entry, market.yesWon, index >= split)
            }
        }

        // Strict frequency screen applies to the FULL 8..4 window, not
        // the full 15-minute market or a sum of duplicate YES/NO mirrors.
        val frequent = stats.values.filter { it.all.count >= minimum }
        val qualified = frequent.flatMap { p ->
            listOf(1, -1).filter { side -> qualifies(p, side) }.map { side ->
                Candidate(p, side)
            }
        }
        // Every path that meets the user's frequency requirement MUST appear,
        // even when its measured returns are negative. For ordering, choose the
        // better historical side; always display BOTH YES and NO for each path.
        val ranked = frequent.map { p ->
            Candidate(p, if (p.all.totalNetCents(1) >= p.all.totalNetCents(-1)) 1 else -1)
        }.sortedWith(compareByDescending<Candidate> { it.totalNet }
            .thenByDescending { it.p.all.count }
            .thenBy { it.p.path.first }
            .thenBy { it.p.path.second })

        val out = StringBuilder(18000)
        val oldest = Instant.ofEpochSecond(ordered.first().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        val newest = Instant.ofEpochSecond(ordered.last().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        out.appendLine("=== FREQUENT BTC KALSHI TWO-POINT PATTERNS ===")
        out.appendLine("Scanned: " + ordered.size +
            " DISTINCT settled 15-minute markets (target 1,000). Skipped loads: " + skipped)
        out.appendLine("Eastern dates: " + oldest + " to " + newest +
            " | existing 10 AM-11 PM Eastern market filter unchanged.")
        if (ordered.size != 1000)
            out.appendLine("INCOMPLETE SCAN: Treat all results as preliminary; never claim 1,000 were checked.")
        out.appendLine("Observed valid quote closes: " + validMinuteCloses +
            " | markets missing at least one 8-to-4-minute close: " + noFullWindow)
        out.appendLine("Only FIRST FRESH two-point completion within 8-4 minutes LEFT is counted.")
        out.appendLine("Minimum to SHOW any pattern: " + minimum +
            " distinct markets AND >=10% of ALL scanned markets. No exceptions.")
        out.appendLine("Tested 56 unique YES paths and their NO mirrors, not 112 independent events.")
        out.appendLine("YES/NO numbers are real historical Kalshi quote MIDPOINTS; NOT the chart's BTC-distance lines.")
        out.appendLine("The NO mirror is 100 minus each YES level. Both sides use ACTUAL settlements.")
        out.appendLine("Entry price = observed buy ASK after the second point, not the named threshold.")
        out.appendLine("All entries are hypothetical; 1-minute closes do not prove orders were fillable.")
        out.appendLine("Net includes approximate one-contract taker fees, excludes slippage.")
        out.appendLine("Strict pass screen AFTER display: positive estimated net in BOTH chronological groups,")
        out.appendLine("older >=20, newest >=10, and Wilson 95% lower win-rate > average all-in cost.")
        out.appendLine("Hundreds of patterns/time choices are tested: selection bias remains, even with these checks.")
        out.appendLine()
        out.appendLine("Frequent patterns (shown below): " + frequent.size +
            " | stricter qualified buy-side setups: " + qualified.size)
        out.appendLine("ALL frequent patterns are listed, whether profitable or not.")
        out.appendLine("Ranking: best historical side's total one-contract net across the dataset,")
        out.appendLine("then hit frequency. A ranking is NOT a recommendation.")
        out.appendLine("PASS/FAIL uses positive historical net in both older/newer samples")
        out.appendLine("and Wilson 95% lower win-rate above estimated all-in entry cost.")
        out.appendLine()
        if (frequent.isEmpty()) {
            out.appendLine("NONE FOUND: no two-point path completed at least " +
                minimum + " times between 8 and 4 minutes left.")
        } else if (qualified.isEmpty()) {
            out.appendLine("0 STRICT PASSES. The " + frequent.size +
                " frequent patterns are still displayed below for research.")
            out.appendLine("Do not treat their historical win rates as profitable signals.")
            out.appendLine()
        }

        for ((index, c) in ranked.withIndex()) {
            val p = c.p
            val s = p.all
            val favoredSide = c.side
            val bestPass = qualifies(p, favoredSide)
            out.appendLine((index + 1).toString() + ") " + p.path.label() +
                " | " + s.count + "/" + ordered.size + " hits (" +
                percent(s.count, ordered.size) + ") | strongest historical side: " +
                (if (favoredSide == 1) "YES" else "NO") +
                " | " + (if (bestPass) "STRICT PASS" else "NOT QUALIFIED"))
            for (side in listOf(1, -1)) {
                val cost = s.costCents(side)
                val multiple = if (cost > 0) num(100.0 / cost) + "x" else "n/a"
                val roi = if (cost > 0)
                    signed(100.0 * s.netCents(side) / cost) + "%" else "n/a"
                val status = if (qualifies(p, side)) "PASS" else "FAIL"
                out.appendLine("   " + (if (side == 1) "YES" else "NO") +
                    " | " + details(s, side) +
                    " | break-even win~" + num(cost) + "%" +
                    " | hist. ROI=" + roi + " | all-in payout~" + multiple +
                    " | total historical net=$" +
                    money(s.totalNetCents(side) / 100.0) +
                    " | " + status)
                out.appendLine("      Older n=" + p.older.count +
                    ": " + details(p.older, side))
                out.appendLine("      Newest n=" + p.newer.count +
                    ": " + details(p.newer, side))
                out.appendLine("      " + bestTiming(p, side))
            }
            out.appendLine("   Exact completion minute left: " +
                (8 downTo 4).joinToString(" | ") { minute ->
                    val b = p.byMinute[minute]
                    minute.toString() + "m n=" + b.count +
                        " YES=" + (if (b.count == 0) "n/a" else num(b.winPct(1)) + "%") +
                        " NO=" + (if (b.count == 0) "n/a" else num(b.winPct(-1)) + "%")
                })
            out.appendLine()
        }

        val frequentUnion = frequent.flatMap { it.marketIndexes }.toSet()
        val qualifiedUnion = qualified.flatMap { it.p.marketIndexes }.toSet()
        out.appendLine("COMBINED coverage of frequent paths (unique markets): " +
            frequentUnion.size + "/" + ordered.size + " (" +
            percent(frequentUnion.size, ordered.size) + ").")
        out.appendLine("COMBINED coverage of strict-pass paths (unique markets): " +
            qualifiedUnion.size + "/" + ordered.size + " (" +
            percent(qualifiedUnion.size, ordered.size) + ").")
        out.appendLine("Overlapping paths or YES/NO mirrors DO NOT add independent opportunities.")
        out.appendLine("Choosing one trade per market needs separate live priority and fill checks.")
        out.appendLine("A 60% historical win rate at a ~40c ask can be valuable;")
        out.appendLine("a 90% win rate at a ~90c ask may not be. Cost and frequency both matter.")
        out.appendLine("Backtest is research only: no live trading rules, market downloads, or alerts changed.")
        out.appendLine("Validate selected patterns on UNTOUCHED future markets before trusting them.")
        out.appendLine("END REPORT")
        return out.toString()
    }
}
