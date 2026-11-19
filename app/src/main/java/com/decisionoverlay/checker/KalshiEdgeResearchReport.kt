package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Research-only, two-sided Kalshi quote path backtest.
 *
 * The path percentages are observed YES bid/ask MIDPOINTS. They are NOT the
 * Binance/chart distance-to-target percentages. The outcome probabilities
 * are measured from settlements, independently of the quoted market prices.
 *
 * Every path is matched at most ONCE per 15-minute market, at its first
 * observable completion. NO paths are exact complements of YES paths and
 * MUST NOT be counted as extra trading opportunities.
 */
object KalshiEdgeResearchReport {
    internal data class Path(val steps: List<Int>) {
        fun yesLabel(): String = steps.joinToString("->")
        fun noMirror(): String = steps.joinToString("->") { (100 - it).toString() }
        fun kind(): String {
            if (steps.size == 2) return if (steps[1] > steps[0]) "UP TREND" else "DOWN TREND"
            val first = steps[1] - steps[0]
            val next = steps[2] - steps[1]
            return if (first * next < 0) "REVERSAL" else "CONTINUATION"
        }
    }

    // The exact seven supplied by the user. Their NO-side mirrors are labels
    // for these SAME events, not seven additional market observations.
    private val originals = listOf(
        Path(listOf(40, 60)), Path(listOf(50, 60)),
        Path(listOf(40, 20)), Path(listOf(50, 20)),
        Path(listOf(30, 20)), Path(listOf(60, 20)),
        Path(listOf(70, 60))
    )
    private val levels = (20..90 step 10).toList()
    private val pairs = levels.flatMap { a ->
        levels.filter { it != a }.map { b -> Path(listOf(a, b)) }
    }
    private val triples = levels.flatMap { a ->
        levels.filter { it != a }.flatMap { b ->
            levels.filter { it != b }.map { c -> Path(listOf(a, b, c)) }
        }
    }
    private data class Window(val name: String, val minLeft: Int, val maxLeft: Int)
    private val windows = listOf(
        Window("15-13", 13, 15), Window("12-9", 9, 12),
        Window("8-6", 6, 8), Window("5-4", 4, 5),
        Window("3-1", 1, 3)
    )

    internal class Bucket {
        var n = 0
        var yesWins = 0
        var yesMidSum = 0.0
        var yesAskSum = 0.0
        var noAskSum = 0.0

        fun add(quote: KalshiPatternMath.Quote, yesWon: Boolean) {
            n++
            if (yesWon) yesWins++
            yesMidSum += quote.strength(1)
            yesAskSum += 100.0 * quote.entryAsk(1)
            noAskSum += 100.0 * quote.entryAsk(-1)
        }

        fun merge(b: Bucket) {
            n += b.n
            yesWins += b.yesWins
            yesMidSum += b.yesMidSum
            yesAskSum += b.yesAskSum
            noAskSum += b.noAskSum
        }

        fun wins(yes: Boolean) = if (yes) yesWins else n - yesWins
        fun winPct(yes: Boolean) = if (n == 0) Double.NaN else 100.0 * wins(yes) / n
        fun quotePct(yes: Boolean) = if (n == 0) Double.NaN
            else if (yes) yesMidSum / n else 100.0 - yesMidSum / n
        fun askPct(yes: Boolean) = if (n == 0) Double.NaN
            else if (yes) yesAskSum / n else noAskSum / n
        fun quoteGap(yes: Boolean) = winPct(yes) - quotePct(yes)
        fun askGap(yes: Boolean) = winPct(yes) - askPct(yes)
    }

    private class Result(val path: Path) {
        val all = Bucket()
        val older = Bucket()
        val newer = Bucket()
        val byMinute = Array(16) { Bucket() }
        val tickers = HashSet<String>()

        fun add(m: KalshiPatternMath.Market, q: KalshiPatternMath.Quote, recent: Boolean) {
            if (!tickers.add(m.ticker)) return
            all.add(q, m.yesWon)
            (if (recent) newer else older).add(q, m.yesWon)
            byMinute[q.left].add(q, m.yesWon)
        }

        fun byWindow(window: Window): Bucket {
            val aggregate = Bucket()
            for (minute in window.minLeft..window.maxLeft) aggregate.merge(byMinute[minute])
            return aggregate
        }
    }

    private fun oneDecimal(value: Double): String =
        if (!value.isFinite()) "n/a" else String.format(Locale.US, "%.1f", value)

    private fun pct(num: Int, den: Int): String =
        if (den == 0) "n/a" else oneDecimal(100.0 * num / den) + "%"

    private fun signed(value: Double): String =
        if (!value.isFinite()) "n/a" else String.format(Locale.US, "%+.1fpp", value)

    private fun statsLine(b: Bucket, den: Int): String {
        if (b.n == 0) return "n=0/" + den + " (0.0% hit), no observations"
        val yes = b.winPct(true)
        val no = b.winPct(false)
        return "n=" + b.n + "/" + den + " (" + pct(b.n, den) + " hit)" +
            " | YES wins=" + b.yesWins + "/" + b.n + " (" + oneDecimal(yes) + "%)" +
            " NO wins=" + (b.n - b.yesWins) + "/" + b.n + " (" + oneDecimal(no) + "%)" +
            " | Kalshi quote YES=" + oneDecimal(b.quotePct(true)) +
            "% NO=" + oneDecimal(b.quotePct(false)) + "%" +
            " | observed ASK YES=" + oneDecimal(b.askPct(true)) +
            "c NO=" + oneDecimal(b.askPct(false)) + "c" +
            " | historical-minus-QUOTE YES=" + signed(b.quoteGap(true)) +
            " NO=" + signed(b.quoteGap(false)) +
            " | historical-minus-ASK YES=" + signed(b.askGap(true)) +
            " NO=" + signed(b.askGap(false))
    }

    private fun label(r: Result): String = "YES " + r.path.yesLabel() +
        " = NO " + r.path.noMirror() + " [same event; " + r.path.kind() + "]"

    private fun flag(r: Result): String {
        if (r.all.n == 0) return "NO MATCHES"
        val sides = listOf(true, false).filter { r.all.askGap(it) >= 20.0 }
        val gap = if (sides.isEmpty()) "NO 20pp ASK GAP" else
            ">=20pp ASK GAP " + sides.joinToString("+") { if (it) "YES" else "NO" }
        val sample = if (r.all.n < 30 || r.older.n < 20 || r.newer.n < 10)
            "SMALL / INCONSISTENT SAMPLE" else "MINIMUM SAMPLE MET"
        return gap + "; " + sample
    }

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No historical settled Kalshi markets to test."
        val ordered = markets.distinctBy { it.ticker }
            .sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }.thenBy { it.ticker })
        val allPaths = pairs + triples
        val result = allPaths.associateWith { Result(it) }
        val split = ordered.size * 7 / 10
        var observedQuotes = 0
        var missingFullMinutes = 0

        for ((i, market) in ordered.withIndex()) {
            val quotes = market.quotes.asSequence().filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }.toList()
            observedQuotes += quotes.size
            if (quotes.size < 15) missingFullMinutes++
            if (quotes.size < 2) continue

            for (path in allPaths) {
                // A quote is not automatically a fill; stages are observed on
                // different completed minutes, never reconstructed from later data.
                val completed = KalshiMirrorResearchReport.firstCompletion(quotes, path.steps)
                if (completed < 0) continue
                val entry = quotes[completed]
                if (entry.entryAsk(1) !in 0.01..0.99 ||
                    entry.entryAsk(-1) !in 0.01..0.99) continue
                result.getValue(path).add(market, entry, i >= split)
            }
        }

        val out = StringBuilder(220000)
        out.appendLine("=== KALSHI TWO-SIDED PATTERN, TIMING & COVERAGE BACKTEST ===")
        out.appendLine("Settled unique BTC 15m markets: " + ordered.size +
            " | skipped candle loads: " + skipped +
            " | usable minute-close quotes: " + observedQuotes)
        out.appendLine("Markets missing at least one of 15 minute closes: " + missingFullMinutes +
            " (missing quotes can hide real patterns).")
        val oldest = Instant.ofEpochSecond(ordered.first().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        val newest = Instant.ofEpochSecond(ordered.last().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        out.appendLine("Observed Eastern dates: " + oldest + " through " + newest)
        out.appendLine("Market discovery retains app's existing 10 AM-11 PM ET filter.")
        out.appendLine("QUOTE=Kalshi historical YES bid/ask midpoint; not the BTC chart percentages.")
        out.appendLine("WIN%=settlement frequency in matching markets, learned from outcomes.")
        out.appendLine("HIT%=unique markets matching this exact path / ALL scanned markets.")
        out.appendLine("The NO mirror is just 100 minus each YES quote: DO NOT double-count it.")
        out.appendLine("Historical 70/30 chronological partitions are shown for consistency, NOT an untouched validation.")
        out.appendLine("The quote's market odds NEVER determine which side the backtest labels as winning.")
        out.appendLine("A price pattern reaching a level at one-minute candle close does not guarantee a fill.")
        out.appendLine("ASK=observed one-contract buy-side ask in cents, not a guaranteed fill.")
        out.appendLine("GAP=historical side win percentage MINUS observed side quote or side ask; units are percentage points.")
        out.appendLine("20pp ASK GAP is an exploratory screen, not proof of a future advantage.")
        out.appendLine("NO profitability, fees, or expected-return estimates are used or shown.")
        out.appendLine("Each pattern is evaluated at its FIRST completed minute in each market; rows/patterns overlap.")
        out.appendLine("Only minute-left 1..15 data are observed; no extrapolation or lookahead into settlement.")
        out.appendLine()

        val originalResults = originals.map { result.getValue(it) }
        val originalMarkets = originalResults.flatMap { it.tickers }.toSet()
        val pairMarkets = pairs.flatMap { result.getValue(it).tickers }.toSet()
        val tripleMarkets = triples.flatMap { result.getValue(it).tickers }.toSet()
        out.appendLine("=== TOTAL COVERAGE, DUPLICATES REMOVED ===")
        out.appendLine("Original seven: " + originalMarkets.size + "/" + ordered.size +
            " (" + pct(originalMarkets.size, ordered.size) + " combined hit); mirrors NOT added.")
        out.appendLine("All distinct two-stage YES paths: " + pairMarkets.size + "/" + ordered.size +
            " (" + pct(pairMarkets.size, ordered.size) + " combined hit).")
        out.appendLine("All distinct three-stage YES paths: " + tripleMarkets.size + "/" + ordered.size +
            " (" + pct(tripleMarkets.size, ordered.size) + " combined hit).")
        out.appendLine("All tested paths combined: " + pairMarkets.union(tripleMarkets).size +
            "/" + ordered.size + " (" +
            pct(pairMarkets.union(tripleMarkets).size, ordered.size) + ").")
        out.appendLine("These combined totals are coverage, NOT win rates or independent opportunities.")
        out.appendLine()

        out.appendLine("=== 1) YOUR EXACT SEVEN, WITH OPPOSITE-SIDE MIRRORS ===")
        for ((index, r) in originalResults.withIndex()) {
            out.appendLine((index + 1).toString() + ") " + label(r) +
                " | " + flag(r))
            out.appendLine(" ALL  " + statsLine(r.all, ordered.size))
            out.appendLine(" OLD  " + statsLine(r.older, split))
            out.appendLine(" NEW  " + statsLine(r.newer, ordered.size - split))
            out.appendLine(" BY TIME OF FIRST COMPLETION (minute remaining before settlement):")
            for (w in windows) {
                val b = r.byWindow(w)
                out.appendLine("   " + w.name + "m: " + statsLine(b, ordered.size))
            }
            out.appendLine(" EXACT MINUTES (15 down to 1):")
            for (minute in 15 downTo 1) {
                out.appendLine("   " + minute + "m: " +
                    statsLine(r.byMinute[minute], ordered.size))
            }
            out.appendLine()
        }

        out.appendLine("=== 2) ALL 56 TWO-POINT TRENDS (INCL. ORIGINAL SEVEN) ===")
        for (path in pairs) {
            val r = result.getValue(path)
            out.appendLine(label(r) + " | " + statsLine(r.all, ordered.size) +
                " | " + flag(r))
        }
        out.appendLine()

        out.appendLine("=== 3) ALL 392 THREE-POINT PATHS (REVERSALS + CONTINUATIONS) ===")
        for (path in triples) {
            val r = result.getValue(path)
            out.appendLine(label(r) + " | " + statsLine(r.all, ordered.size) +
                " | " + flag(r))
        }
        out.appendLine()

        data class Timed(val r: Result, val window: Window, val bucket: Bucket, val yes: Boolean)
        val timed = allPaths.flatMap { path ->
            val r = result.getValue(path)
            windows.flatMap { w ->
                val b = r.byWindow(w)
                listOf(true, false).mapNotNull { yes ->
                    if (b.n >= 10 && b.askGap(yes) >= 20.0)
                        Timed(r, w, b, yes)
                    else null
                }
            }
        }.sortedWith(compareByDescending<Timed> { it.bucket.n }
            .thenByDescending { it.bucket.askGap(it.yes) }
            .thenBy { it.r.path.yesLabel() })

        out.appendLine("=== 4) ALL TIME-WINDOW CANDIDATES: >=10 MATCHES, >=20pp ASK GAP ===")
        out.appendLine("Sorted by HIT COUNT first (not simply the highest win rate).")
        out.appendLine("These are exploratory and selected using these same markets.")
        for (t in timed) {
            out.appendLine(label(t.r) + " @ " + t.window.name + "m" +
                " | side=" + (if (t.yes) "YES" else "NO") +
                " | n=" + t.bucket.n + "/" + ordered.size +
                " (" + pct(t.bucket.n, ordered.size) + " hit)" +
                " | wins=" + t.bucket.wins(t.yes) + "/" + t.bucket.n +
                " (" + oneDecimal(t.bucket.winPct(t.yes)) + "%)" +
                " | quote=" + oneDecimal(t.bucket.quotePct(t.yes)) + "%" +
                " | ask=" + oneDecimal(t.bucket.askPct(t.yes)) + "c" +
                " | gap_to_ASK=" + signed(t.bucket.askGap(t.yes)) +
                (if (t.bucket.n < 30) " | SMALL SAMPLE" else ""))
        }
        if (timed.isEmpty()) out.appendLine("No time-window setup met the exploratory screen.")
        val screeningMarkets = timed.flatMap { t ->
            // One observed market belongs to ONE completion minute per path.
            // Match the selected time window without counting YES+NO or other paths twice.
            val path = t.r.path
            val selected = HashSet<String>()
            for (m in ordered) {
                if (m.ticker !in t.r.tickers) continue
                val quotes = m.quotes.filter { it.valid() }
                    .sortedByDescending { it.left }.distinctBy { it.left }
                val at = KalshiMirrorResearchReport.firstCompletion(quotes, path.steps)
                if (at >= 0 && quotes[at].left in t.window.minLeft..t.window.maxLeft)
                    selected.add(m.ticker)
            }
            selected
        }.toSet()
        out.appendLine("Combined distinct-market coverage of time-window candidates: " +
            screeningMarkets.size + "/" + ordered.size +
            " (" + pct(screeningMarkets.size, ordered.size) + ").")
        out.appendLine()

        out.appendLine("=== 5) VALIDATION NOTES ===")
        out.appendLine("The earlier reported 60% coverage is a historical claim, not a constant.")
        out.appendLine("Small samples and hundreds of overlapping rules can produce misleading win%.")
        out.appendLine("A 20pp historical gap on the same sample used for discovery can disappear on new markets.")
        out.appendLine("Run another untouched date range; compare actual available asks before live use.")
        out.appendLine("No live notification or buy/sell rules are changed by this research report.")
        out.appendLine("END REPORT")
        return out.toString()
    }
}
