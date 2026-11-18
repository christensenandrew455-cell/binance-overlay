package com.decisionoverlay.checker

import java.time.Instant
import java.util.Locale
import kotlin.math.floor

/**
 * A detailed but aggregated research report. Every rule is evaluated against the
 * SAME settled market and both possible contracts. No market-by-market dump.
 *
 * Path levels refer to the YES quote MIDPOINT, NOT the BTC spot price.
 * 80>60>70 means YES midpoint >=80, later <=60, later >=70.
 * 60>70 means YES midpoint <=60, later >=70.
 *
 * Each path's FIRST complete occurrence is used once per market. A market can
 * match several distinct paths; ranking them together is NOT independent data.
 */
object KalshiExpandedReport {
    private val levels = (20..90 step 10).toList()
    private val bands = listOf(
        Band("15-13", 13, 15), Band("12-9", 9, 12),
        Band("8-6", 6, 8), Band("5-4", 4, 5), Band("3-1", 1, 3)
    )
    private data class Band(val label: String, val min: Int, val max: Int)
    private data class Path(val steps: List<Int>) {
        fun label() = steps.joinToString(">")
    }
    private data class Mark(val yesMid: Double, val yesAsk: Double,
                            val noAsk: Double, val left: Int)

    private class Stats {
        var n = 0
        var yesWins = 0
        var yesAsk = 0.0
        var noAsk = 0.0
        var yesFee = 0.0
        var noFee = 0.0
        var yesNet = 0.0
        var noNet = 0.0
        var firstLeft = 0.0
        var finalLeft = 0.0
        var oldN = 0
        var oldYesWins = 0
        var oldYesNet = 0.0
        var oldNoNet = 0.0
        var recentN = 0
        var recentYesWins = 0
        var recentYesNet = 0.0
        var recentNoNet = 0.0

        fun add(yesWon: Boolean, mark: Mark, first: Int, recent: Boolean) {
            val feeY = KalshiPatternMath.approximateFee(mark.yesAsk)
            val feeN = KalshiPatternMath.approximateFee(mark.noAsk)
            val netY = (if (yesWon) 1.0 else 0.0) - mark.yesAsk - feeY
            val netN = (if (yesWon) 0.0 else 1.0) - mark.noAsk - feeN
            n++
            if (yesWon) yesWins++
            yesAsk += mark.yesAsk
            noAsk += mark.noAsk
            yesFee += feeY
            noFee += feeN
            yesNet += netY
            noNet += netN
            firstLeft += first
            finalLeft += mark.left
            if (recent) {
                recentN++
                if (yesWon) recentYesWins++
                recentYesNet += netY
                recentNoNet += netN
            } else {
                oldN++
                if (yesWon) oldYesWins++
                oldYesNet += netY
                oldNoNet += netN
            }
        }
    }

    private fun f(v: Double) = String.format(Locale.US, "%.1f", v)
    private fun signed(v: Double) = String.format(Locale.US, "%+.1f", v)
    private fun pct(num: Int, den: Int) =
        if (den == 0) "n/a" else f(100.0 * num / den) + "%"
    private fun cents(sum: Double, n: Int) =
        if (n == 0) "n/a" else f(sum * 100.0 / n) + "c"
    private fun net(sum: Double, n: Int) =
        if (n == 0) "n/a" else signed(sum * 100.0 / n) + "c"
    private fun freq(n: Int, total: Int) =
        f(100.0 * n / total) + "%; ~" + f(52.0 * n / total) + "/day"
    private fun flag(n: Int, recentN: Int) =
        if (n < 30 || recentN < 10) " [SMALL / UNSTABLE]" else ""

    // Stage 0's direction is set by where stage 1 must go. Subsequent
    // stages can turn up OR down, so these rules never force a winning side.
    private fun firstPath(q: List<Mark>, path: Path): IntArray? {
        val found = IntArray(path.steps.size)
        var searchFrom = 0
        for (stage in path.steps.indices) {
            val up = if (stage == 0)
                path.steps[1] > path.steps[0]
            else path.steps[stage] > path.steps[stage - 1]
            val level = path.steps[stage].toDouble()
            var selected = -1
            for (i in searchFrom until q.size) {
                val p = q[i].yesMid
                if ((up && p >= level - 0.0000001) ||
                    (!up && p <= level + 0.0000001)) {
                    selected = i
                    break
                }
            }
            if (selected < 0) return null
            found[stage] = selected
            searchFrom = selected + 1
        }
        return found
    }

    private fun dataLine(label: String, s: Stats, total: Int,
                         detailed: Boolean = true): String {
        val base = label + ": n=" + s.n + "/" + total + " (" + freq(s.n, total) + ")" +
            " | YES " + s.yesWins + "/" + s.n + " " + pct(s.yesWins, s.n) +
            " ask " + cents(s.yesAsk, s.n) + " fee " + cents(s.yesFee, s.n) +
            " net " + net(s.yesNet, s.n) +
            " | NO " + (s.n - s.yesWins) + "/" + s.n + " " +
            pct(s.n - s.yesWins, s.n) +
            " ask " + cents(s.noAsk, s.n) + " fee " + cents(s.noFee, s.n) +
            " net " + net(s.noNet, s.n)
        if (!detailed) return base
        return base + " | avg first/entry min-left " +
            f(s.firstLeft / s.n) + "/" + f(s.finalLeft / s.n) +
            " | newest YES " + s.recentYesWins + "/" + s.recentN +
            " (" + pct(s.recentYesWins, s.recentN) + ")" +
            " YESnet " + net(s.recentYesNet, s.recentN) +
            " NOnet " + net(s.recentNoNet, s.recentN) +
            flag(s.n, s.recentN)
    }

    private fun eligible(m: Mark) = m.yesAsk in 0.01..0.99 &&
        m.noAsk in 0.01..0.99

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No settled Kalshi BTC markets. Skipped: $skipped."
        val sorted = markets.sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }
            .thenBy { it.ticker })
        // Position-based split avoids relying on uniqueness of fake test ticker names.
        val recentStart = sorted.size * 7 / 10
        val counts = LinkedHashMap<Path, Stats>()
        val time = LinkedHashMap<Pair<Path, String>, Stats>()
        val minute = LinkedHashMap<Pair<Path, Int>, Stats>()
        val quoteBins = LinkedHashMap<Pair<Int, String>, Stats>()
        var validQuotes = 0

        for ((marketIndex, market) in sorted.withIndex()) {
            val recent = marketIndex >= recentStart
            val marks = market.quotes.filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }
                .map {
                    Mark(it.strength(1), it.entryAsk(1), it.entryAsk(-1), it.left)
                }.filter { eligible(it) }
            validQuotes += marks.size
            if (marks.isEmpty()) continue
            // A bin is one observed YES midpoint decile in one minute-left
            // range. Count one observation per market/bin/range, not each quote.
            val usedBins = HashSet<Pair<Int, String>>()
            for (p in marks) {
                val bucket = bands.firstOrNull { p.left in it.min..it.max } ?: continue
                val decile = (floor(p.yesMid / 10.0).toInt() * 10).coerceIn(0, 90)
                val key = decile to bucket.label
                if (usedBins.add(key))
                    quoteBins.getOrPut(key) { Stats() }
                        .add(market.yesWon, p, p.left, recent)
            }
            for (a in levels) for (b in levels) {
                if (a == b) continue
                val pair = Path(listOf(a, b))
                val hit = firstPath(marks, pair)
                if (hit != null) {
                    val entry = marks[hit.last()]
                    val first = marks[hit.first()].left
                    counts.getOrPut(pair) { Stats() }.add(market.yesWon, entry, first, recent)
                    val bucket = bands.first { entry.left in it.min..it.max }
                    time.getOrPut(pair to bucket.label) { Stats() }
                        .add(market.yesWon, entry, first, recent)
                    minute.getOrPut(pair to entry.left) { Stats() }
                        .add(market.yesWon, entry, first, recent)
                }
                for (c in levels) {
                    if (c == b) continue
                    val triple = Path(listOf(a, b, c))
                    val complete = firstPath(marks, triple) ?: continue
                    val entry = marks[complete.last()]
                    val first = marks[complete.first()].left
                    counts.getOrPut(triple) { Stats() }
                        .add(market.yesWon, entry, first, recent)
                    val bucket = bands.first { entry.left in it.min..it.max }
                    time.getOrPut(triple to bucket.label) { Stats() }
                        .add(market.yesWon, entry, first, recent)
                    minute.getOrPut(triple to entry.left) { Stats() }
                        .add(market.yesWon, entry, first, recent)
                }
            }
        }

        val out = StringBuilder(110000)
        out.appendLine("=== EXPANDED BTC 15-MINUTE KALSHI PATH BACKTEST ===")
        out.appendLine("Unique settled markets: " + sorted.size + "; skipped/empty: " +
            skipped + "; usable 1-minute quotes: " + validQuotes)
        out.appendLine("UTC market close: " + Instant.ofEpochSecond(sorted.first().closeTs) +
            " to " + Instant.ofEpochSecond(sorted.last().closeTs))
        out.appendLine("Session start 10 AM to 11 PM Eastern; 52 15-minute BTC markets/day.")
        out.appendLine("Every path is defined using the YES 1-minute quote MIDPOINT, not BTC price.")
        out.appendLine("For 80>60>70: YES at/above 80, later at/below 60, later at/above 70.")
        out.appendLine("For 60>70: YES at/below 60, later at/above 70. Stages must be on later minutes.")
        out.appendLine("Path labels are thresholds, NOT actual executable ask prices.")
        out.appendLine("Each row evaluates BOTH YES and NO settlement, asks, fees and net.")
        out.appendLine("Win% = actual side wins / markets with the path, not Kalshi's displayed odds.")
        out.appendLine("Net = actual $1 settlement payout - observed ask - approximate 1-contract taker fee.")
        out.appendLine("Estimated opportunities/day = 52 * unique matching markets / scanned markets.")
        out.appendLine("Frequency is per unique market for EACH rule; different rules overlap.")
        out.appendLine("Each path uses its FIRST completed observation per market, not repeated touches.")
        out.appendLine("Time ranges denote minutes remaining when the path FIRST completes.")
        out.appendLine("15-13 and 3-1 minutes are RESEARCH-ONLY. 12-9, 8-6, 5-4 are normal entry windows.")
        out.appendLine("One-minute samples may skip thresholds; there is no modeled fill, volume or slippage.")
        out.appendLine("Old 70% / newest 30% is chronological, not an untouched selection-free validation.")
        out.appendLine("Small samples and searching many patterns can produce misleading apparent edges.")
        out.appendLine()

        out.appendLine("1) CURRENT YES QUOTE MIDPOINT RANGE x TIME (all observed nonempty bins)")
        out.appendLine("Each bin counts a market once per range AND time window; bins overlap across time.")
        for (d in 0..90 step 10) for (bucket in bands) {
            val s = quoteBins[d to bucket.label] ?: continue
            out.appendLine(dataLine("YESmid " + d + "-" + (d + 10) + "% / " +
                bucket.label + "m", s, sorted.size, false))
        }
        out.appendLine()

        out.appendLine("2) ALL TWO-LEVEL MOVEMENTS (YES quote thresholds; up AND down)")
        out.appendLine("Each pair is first observed A, then B in a later minute.")
        val pairs = counts.entries.filter { it.key.steps.size == 2 }
            .sortedWith(compareBy<Map.Entry<Path, Stats>> { it.key.steps[0] }
                .thenBy { it.key.steps[1] })
        for ((path, s) in pairs) out.appendLine(dataLine(path.label(), s, sorted.size))
        out.appendLine()

        out.appendLine("3) ALL THREE-LEVEL MOVEMENTS (including flips, returns and continuations)")
        val triples = counts.entries.filter { it.key.steps.size == 3 }
            .sortedWith(compareBy<Map.Entry<Path, Stats>> { it.key.steps[0] }
                .thenBy { it.key.steps[1] }.thenBy { it.key.steps[2] })
        for ((path, s) in triples) out.appendLine(dataLine(path.label(), s, sorted.size))
        out.appendLine()

        out.appendLine("4) TIME WINDOW BREAKDOWN FOR EACH PATH WITH >=10 MATCHES")
        out.appendLine("Each row is the first completed signal in that time window. YES and NO evaluated together.")
        val byVolume = counts.entries.filter { it.value.n >= 10 }
            .sortedWith(compareByDescending<Map.Entry<Path, Stats>> { it.value.n }
                .thenBy { it.key.label() })
        for ((path, _) in byVolume) {
            out.appendLine("PATH " + path.label())
            for (bucket in bands) {
                val s = time[path to bucket.label] ?: continue
                out.appendLine("  " + bucket.label + "m n=" + s.n +
                    " | YES " + pct(s.yesWins, s.n) + " net " + net(s.yesNet, s.n) +
                    " | NO " + pct(s.n - s.yesWins, s.n) +
                    " net " + net(s.noNet, s.n) +
                    " | YES ask " + cents(s.yesAsk, s.n) +
                    " NO ask " + cents(s.noAsk, s.n))
            }
        }
        out.appendLine()

        out.appendLine("5) EXACT MINUTES LEFT FOR 20 MOST FREQUENT PATHS")
        out.appendLine("Minutes with no matching market are omitted; this is not a per-market dump.")
        for ((path, _) in byVolume.take(20)) {
            out.appendLine("PATH " + path.label())
            for (left in 15 downTo 1) {
                val s = minute[path to left] ?: continue
                out.appendLine("  " + left + "m: n=" + s.n +
                    " YESwins=" + pct(s.yesWins, s.n) +
                    " NOwins=" + pct(s.n - s.yesWins, s.n) +
                    " YESnet=" + net(s.yesNet, s.n) +
                    " NOnet=" + net(s.noNet, s.n))
            }
        }
        out.appendLine()

        out.appendLine("6) HIGH-FREQUENCY STRATEGY CANDIDATES, SELECTED ONLY BY OLDER 70%")
        out.appendLine("Ranking = older frequency/day * older average NET cents per contract.")
        out.appendLine("Winner side chosen ONLY from older data; newest 30% is shown for comparison.")
        val oldTotal = recentStart.coerceAtLeast(1)
        val newTotal = (sorted.size - recentStart).coerceAtLeast(1)
        data class Candidate(val key: Path, val stat: Stats, val side: String,
                             val olderDailyNet: Double)
        val ranked = counts.mapNotNull { (key, s) ->
            if (s.oldN < 35 || s.recentN < 15) null else {
                val bestYes = s.oldYesNet >= s.oldNoNet
                val chosen = if (bestYes) s.oldYesNet else s.oldNoNet
                Candidate(key, s, if (bestYes) "YES" else "NO", 52.0 * chosen / oldTotal)
            }
        }.sortedByDescending { it.olderDailyNet }.take(30)
        for (c in ranked) {
            val s = c.stat
            val oldChosen = if (c.side == "YES") s.oldYesNet else s.oldNoNet
            val recentChosen = if (c.side == "YES") s.recentYesNet else s.recentNoNet
            val winsOld = if (c.side == "YES") s.oldYesWins else s.oldN - s.oldYesWins
            val winsNew = if (c.side == "YES") s.recentYesWins else s.recentN - s.recentYesWins
            out.appendLine(c.key.label() + " BUY " + c.side +
                " | old n=" + s.oldN + "/" + oldTotal + " (" +
                pct(s.oldN, oldTotal) + "), wins " + pct(winsOld, s.oldN) +
                " net " + net(oldChosen, s.oldN) +
                " dailyEV " + signed(c.olderDailyNet) + "c" +
                " | newest n=" + s.recentN + "/" + newTotal +
                " (" + pct(s.recentN, newTotal) + "), wins " +
                pct(winsNew, s.recentN) + " net " + net(recentChosen, s.recentN))
        }
        if (ranked.isEmpty()) out.appendLine("No rule has enough examples for ranking.")
        out.appendLine()
        out.appendLine("END REPORT. A positive historical net is NOT a verified tradable edge.")
        out.appendLine("Quotes are sparse one-minute closes, not guaranteed execution prices.")
        out.appendLine("Use independent future data and realistic order fills before risking money.")
        return out.toString()
    }
}
