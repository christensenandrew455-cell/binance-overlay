package com.decisionoverlay.checker

import java.util.Locale

/**
 * Research-only evaluation of seven specified price paths, their seven
 * reflections, and other two/three-point quote paths. Does not issue alerts.
 *
 * This scanner consumes actual Kalshi YES quote midpoints, NOT the Binance
 * chart's custom signed index. Labels such as -80 correspond to YES midpoint
 * 20 here, solely for symmetry; the readings are not interchangeable.
 */
object KalshiMirrorResearchReport {
    private data class Rule(val levels: List<Int>, val side: Int,
                            val mirrored: Boolean) {
        fun label(): String = levels.joinToString(" -> ") { if (it >= 0) "+$it" else "$it" }
        fun quotePath(): List<Int> = levels.map { if (it >= 0) it else 100 + it }
    }

    private val originals = listOf(
        Rule(listOf(70, 50), -1, false),
        Rule(listOf(70, 60), -1, false),
        Rule(listOf(80, 70), -1, false),
        Rule(listOf(70, 60, 50), -1, false),
        Rule(listOf(50, 40, 50), 1, false),
        Rule(listOf(60, 80, 70), -1, false),
        Rule(listOf(60, 50, 70), 1, false)
    )
    private val rules: List<Rule> = originals + originals.map {
        Rule(it.levels.map { level -> -level }, -it.side, true)
    }
    private data class Window(val label: String, val low: Int, val high: Int)
    private val windows = listOf(
        Window("15-13", 13, 15), Window("12-9", 9, 12),
        Window("8-6", 6, 8), Window("5-4", 4, 5),
        Window("3-1", 1, 3)
    )

    private class Bucket {
        var n = 0
        var yesWins = 0
        var yesAsk = 0.0
        var noAsk = 0.0
        var yesFees = 0.0
        var noFees = 0.0
        var yesNet = 0.0
        var noNet = 0.0

        fun add(market: KalshiPatternMath.Market,
                quote: KalshiPatternMath.Quote) {
            val yes = quote.entryAsk(1)
            val no = quote.entryAsk(-1)
            if (yes !in 0.01..0.99 || no !in 0.01..0.99) return
            val yesFee = KalshiPatternMath.approximateFee(yes)
            val noFee = KalshiPatternMath.approximateFee(no)
            n++
            if (market.yesWon) yesWins++
            yesAsk += yes
            noAsk += no
            yesFees += yesFee
            noFees += noFee
            yesNet += (if (market.yesWon) 1.0 else 0.0) - yes - yesFee
            noNet += (if (market.yesWon) 0.0 else 1.0) - no - noFee
        }
        fun wins(side: Int) = if (side == 1) yesWins else n - yesWins
        fun ask(side: Int) = if (side == 1) yesAsk else noAsk
        fun fees(side: Int) = if (side == 1) yesFees else noFees
        fun net(side: Int) = if (side == 1) yesNet else noNet
        fun winRate(side: Int) = if (n == 0) 0.0 else wins(side).toDouble() / n
        fun averageNet(side: Int) = if (n == 0) 0.0 else net(side) / n
    }
    private class Stats {
        val all = Bucket()
        val older = Bucket()
        val newest = Bucket()
        fun add(market: KalshiPatternMath.Market,
                quote: KalshiPatternMath.Quote, recent: Boolean) {
            all.add(market, quote)
            if (recent) newest.add(market, quote) else older.add(market, quote)
        }
    }

    private fun fmt(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun pct(a: Int, b: Int) = if (b == 0) "n/a" else fmt(100.0 * a / b) + "%"
    private fun value(x: Double, n: Int) =
        if (n == 0) "n/a" else fmt(100.0 * x / n) + "c"
    private fun signedValue(x: Double, n: Int) =
        if (n == 0) "n/a" else String.format(Locale.US, "%+.1fc", 100.0 * x / n)

    /** Every stage is observed in a LATER completed one-minute interval. */
    internal fun firstCompletion(ordered: List<KalshiPatternMath.Quote>,
                                 path: List<Int>): Int {
        if (path.size < 2) return -1
        var from = 0
        var last = -1
        for (stage in path.indices) {
            val rising = if (stage == 0) path[1] > path[0]
                         else path[stage] > path[stage - 1]
            var found = -1
            for (i in from until ordered.size) {
                val level = ordered[i].strength(1)
                val reached = if (rising) {
                    if (stage == 0) level <= path[stage] + 1e-7
                    else level >= path[stage] - 1e-7
                } else {
                    if (stage == 0) level >= path[stage] - 1e-7
                    else level <= path[stage] + 1e-7
                }
                if (reached) { found = i; break }
            }
            if (found < 0) return -1
            last = found
            from = found + 1
        }
        return last
    }

    private fun detail(b: Bucket, side: Int): String =
        "matches=" + b.n + ", wins=" + b.wins(side) + "/" + b.n +
        " (" + pct(b.wins(side), b.n) + ")" +
        ", ask=" + value(b.ask(side), b.n) +
        ", fee=" + value(b.fees(side), b.n) +
        ", estimated net=" + signedValue(b.net(side), b.n)

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No usable settled markets; skipped: " + skipped
        val ordered = markets.sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }
            .thenBy { it.ticker })
        val split = ordered.size * 7 / 10
        val stats = List(rules.size) { Stats() }
        val byWindow = List(rules.size) { List(windows.size) { Stats() } }
        val marketsOriginal = HashSet<Int>()
        val marketsMirror = HashSet<Int>()
        val marketsAny = HashSet<Int>()
        val marketsWindow = Array(windows.size) { HashSet<Int>() }
        val quoteCandidates = ArrayList<List<Int>>()
        for (a in 20..90 step 10) for (b in 20..90 step 10) {
            if (a == b) continue
            quoteCandidates.add(listOf(a, b))
            for (c in 20..90 step 10) {
                if (c != b) quoteCandidates.add(listOf(a, b, c))
            }
        }
        val candidateStats = List(quoteCandidates.size) { Stats() }

        for ((idx, market) in ordered.withIndex()) {
            val quotes = market.quotes.filter { it.valid() &&
                it.entryAsk(1) in 0.01..0.99 &&
                it.entryAsk(-1) in 0.01..0.99 }
                .sortedByDescending { it.left }.distinctBy { it.left }
            if (quotes.size < 2) continue
            val recent = idx >= split
            for ((r, rule) in rules.withIndex()) {
                val completion = firstCompletion(quotes, rule.quotePath())
                if (completion < 0) continue
                val entry = quotes[completion]
                stats[r].add(market, entry, recent)
                marketsAny.add(idx)
                if (rule.mirrored) marketsMirror.add(idx) else marketsOriginal.add(idx)
                val bucket = windows.indexOfFirst { entry.left in it.low..it.high }
                if (bucket >= 0) {
                    byWindow[r][bucket].add(market, entry, recent)
                    marketsWindow[bucket].add(idx)
                }
            }
            for (c in quoteCandidates.indices) {
                val hit = firstCompletion(quotes, quoteCandidates[c])
                if (hit >= 0) candidateStats[c].add(market, quotes[hit], recent)
            }
        }

        val out = StringBuilder()
        val total = ordered.size
        out.appendLine("=== 1,000-MARKET BTC PATTERN + MIRROR RESEARCH ===")
        out.appendLine("Actually analyzed: " + total + " settled markets; skipped: " + skipped)
        out.appendLine("Source: Kalshi ONE-MINUTE YES bid/ask quote closes and actual settlements.")
        out.appendLine("IMPORTANT: THESE ARE NOT THE APP'S BINANCE CHART PERCENTAGES.")
        out.appendLine("A label +80 means Kalshi YES midpoint 80; -80 means midpoint 20.")
        out.appendLine("These sign labels describe mirrored quote paths ONLY; chart mapping is unverified.")
        out.appendLine("Stages must occur on later one-minute candles; entry is the last stage.")
        out.appendLine("Each pattern is counted once per market. Different patterns can overlap.")
        out.appendLine("Win rate is historical settlement frequency AFTER full pattern completion.")
        out.appendLine("Hit rate is matching markets divided by ALL analyzed markets.")
        out.appendLine("Fee is estimated for one taker contract; quotes do not guarantee fills.")
        out.appendLine("Older/newer are chronological 70%/30%, NOT independent proof of edge.")
        out.appendLine("No rules or live alerts have been changed by this report.")
        out.appendLine()
        out.appendLine("=== COMBINED COVERAGE (DUPLICATES REMOVED) ===")
        val raw = stats.sumOf { it.all.n }
        out.appendLine("All 14: " + marketsAny.size + "/" + total + " distinct markets (" +
            pct(marketsAny.size, total) + "), raw pattern matches=" + raw)
        out.appendLine("Original 7: " + marketsOriginal.size + "/" + total + " (" +
            pct(marketsOriginal.size, total) + ")")
        out.appendLine("Mirrored 7: " + marketsMirror.size + "/" + total + " (" +
            pct(marketsMirror.size, total) + ")")
        out.appendLine("Markets with BOTH an original and a mirror=" +
            marketsOriginal.intersect(marketsMirror).size)
        out.appendLine("Approx distinct active-hours opportunities/day=" +
            fmt(52.0 * marketsAny.size / total) +
            " (assuming observed markets resemble 10am-11pm ET future markets)")
        for (i in windows.indices) {
            out.appendLine(windows[i].label + " minutes remaining: " +
                marketsWindow[i].size + "/" + total + " distinct markets (" +
                pct(marketsWindow[i].size, total) + ")")
        }
        out.appendLine("Window counts may overlap across windows; do not sum them.")
        out.appendLine()
        out.appendLine("=== EACH OF THE 14 PATTERNS ===")
        for ((i, rule) in rules.withIndex()) {
            val s = stats[i]
            val side = if (rule.side == 1) "UP (YES)" else "DOWN (NO)"
            out.appendLine((i + 1).toString() + ". " + rule.label() + " | " +
                (if (rule.mirrored) "MIRROR" else "ORIGINAL") + " | test " + side)
            out.appendLine("  Hit rate: " + s.all.n + "/" + total +
                " = " + pct(s.all.n, total) +
                " | ~" + fmt(52.0 * s.all.n / total) + " raw matches/day")
            out.appendLine("  Overall: " + detail(s.all, rule.side))
            out.appendLine("  Opposite side: " + detail(s.all, -rule.side))
            out.appendLine("  Older: " + detail(s.older, rule.side))
            out.appendLine("  Newest: " + detail(s.newest, rule.side))
            for (w in windows.indices) {
                val ws = byWindow[i][w].all
                out.appendLine("  " + windows[w].label + "m: " +
                    ws.n + "/" + total + " hit (" + pct(ws.n, total) + ")" +
                    " | wins " + pct(ws.wins(rule.side), ws.n) +
                    " | net " + signedValue(ws.net(rule.side), ws.n))
            }
            val enough = s.older.n >= 20 && s.newest.n >= 10
            val qualified = enough && s.older.winRate(rule.side) >= .60 &&
                s.newest.winRate(rule.side) >= .60 &&
                s.older.averageNet(rule.side) > 0.0 &&
                s.newest.averageNet(rule.side) > 0.0
            out.appendLine("  Screen (>=60% wins and positive modeled net in BOTH periods, " +
                "older n>=20, newer n>=10): " +
                (if (qualified) "PASS - still experimental" else "NOT QUALIFIED"))
            out.appendLine()
        }

        // An exploratory search is separate from the requested 14. It is not
        // an automatically approved live strategy; data-mining inflates winners.
        data class Candidate(val path: List<Int>, val side: Int, val s: Stats)
        val selectedPaths = rules.map { it.quotePath() }.toSet()
        val discovered = quoteCandidates.indices.flatMap { i ->
            val s = candidateStats[i]
            if (quoteCandidates[i] in selectedPaths ||
                s.older.n < 20 || s.newest.n < 10) emptyList()
            else listOf(1, -1).mapNotNull { side ->
                if (s.older.winRate(side) >= .60 &&
                    s.newest.winRate(side) >= .60 &&
                    s.older.averageNet(side) > 0.0 &&
                    s.newest.averageNet(side) > 0.0)
                    Candidate(quoteCandidates[i], side, s)
                else null
            }
        }.sortedWith(compareByDescending<Candidate> {
            it.s.newest.averageNet(it.side)
        }.thenByDescending { it.s.newest.n })

        out.appendLine("=== NEW TWO/THREE-POINT CANDIDATES (RESEARCH ONLY) ===")
        out.appendLine("Scanned " + quoteCandidates.size + " quote paths in both directions.")
        out.appendLine("Filter: >=60% wins and positive modeled net in older AND newest groups;")
        out.appendLine("minimum matches: older 20, newest 10; excludes the 14 requested paths.")
        out.appendLine("These are discovery samples, NOT out-of-sample confirmations.")
        for (c in discovered.take(25)) {
            out.appendLine(c.path.joinToString(" -> ") + " | " +
                (if (c.side == 1) "UP" else "DOWN") +
                " | hit=" + c.s.all.n + "/" + total +
                " (" + pct(c.s.all.n, total) + ")" +
                " | older " + pct(c.s.older.wins(c.side), c.s.older.n) +
                " n=" + c.s.older.n +
                " net " + signedValue(c.s.older.net(c.side), c.s.older.n) +
                " | newest " + pct(c.s.newest.wins(c.side), c.s.newest.n) +
                " n=" + c.s.newest.n +
                " net " + signedValue(c.s.newest.net(c.side), c.s.newest.n))
        }
        if (discovered.isEmpty()) out.appendLine("None passed the research screen.")
        if (discovered.size > 25)
            out.appendLine("More than 25 qualified; displayed strongest 25 by newest modeled net.")
        out.appendLine()
        out.appendLine("END. Historical results are not calibrated to the live custom chart.")
        out.appendLine("Validate separately with future markets and actual executable prices.")
        return out.toString()
    }
}
