package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Compact historical two-point pattern scanner. Input is the EXISTING
 * downloaded, settled BTC Kalshi markets; no live orders or alerts.
 *
 * The path is labeled using YES midpoints. BUY UP means YES and BUY DOWN
 * means NO. NO 80->60 is the mirror of YES 20->40, not an independent event.
 */
object KalshiFrequentPatternReport {
    private const val FIRST_MINUTE_LEFT = 8
    private const val LAST_MINUTE_LEFT = 4
    private const val MIN_ABSOLUTE_MATCHES = 100
    private const val MIN_MARKET_FRACTION = 0.10
    private const val MIN_RAW_GAP_POINTS = 20.0
    private val levels = (20..90 step 10).toList()

    internal data class PairPath(val first: Int, val second: Int) {
        fun label() = "YES " + first + "->" + second +
            " / NO " + (100 - first) + "->" + (100 - second)
    }

    private val paths = levels.flatMap { a ->
        levels.filter { it != a }.map { b -> PairPath(a, b) }
    }

    /**
     * Count only the first FRESH two-stage completion between 8 and 4
     * minutes left, with points occurring on separate minute closes.
     * A completion before the time window disarms the setup; it must
     * reach the first threshold again to qualify later.
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
                val laterMinute = quote.left < firstObservedLeft
                armed = false
                if (laterMinute &&
                    quote.left in LAST_MINUTE_LEFT..FIRST_MINUTE_LEFT)
                    return quote
            }
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

        fun add(quote: KalshiPatternMath.Quote, yesWon: Boolean) {
            count++
            if (yesWon) yesWins++
            val yes = quote.entryAsk(1)
            val no = quote.entryAsk(-1)
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
        fun winPct(side: Int): Double =
            if (count == 0) 0.0 else 100.0 * wins(side) / count
        fun costCents(side: Int): Double =
            if (count == 0) 0.0 else 100.0 *
                (if (side == 1) yesAsk + yesFees else noAsk + noFees) / count
        fun netCents(side: Int): Double =
            if (count == 0) 0.0 else 100.0 * wins(side) / count -
                costCents(side)
        fun roi(side: Int): Double =
            if (costCents(side) > 0.0) 100.0 * netCents(side) / costCents(side)
            else Double.NEGATIVE_INFINITY
    }

    private class PathStats(val path: PairPath) {
        val byMinute = Array(16) { Stats() }
        val indexes = HashSet<Int>()
        fun add(index: Int, quote: KalshiPatternMath.Quote, yesWon: Boolean) {
            if (indexes.add(index)) byMinute[quote.left].add(quote, yesWon)
        }
        fun window(high: Int, low: Int): Stats {
            val sum = Stats()
            for (minute in low..high) sum.merge(byMinute[minute])
            return sum
        }
    }

    private data class Candidate(
        val p: PathStats,
        val side: Int,
        val highMinute: Int,
        val lowMinute: Int,
        val stats: Stats
    ) {
        val gapPoints: Double
            get() = abs(stats.winPct(side) - p.path.second)
        val hitCount: Int get() = stats.count
        val roi: Double get() = stats.roi(side)
    }

    private fun eligible(count: Int, total: Int): Boolean =
        count >= MIN_ABSOLUTE_MATCHES &&
            count.toDouble() / total > MIN_MARKET_FRACTION

    /**
     * The user-requested difference is the absolute distance between the
     * BUY SIDE'S settlement win rate and the LAST YES chart percentage.
     * This is NOT the fee-adjusted price edge: for DOWN the displayed
     * YES percentage is not the NO buy price.
     *
     * Restrict each candidate's chosen timing to ranges of >=2 consecutive
     * minutes within 8..4, all independently over the strict frequency floor.
     * The highest historical win rate wins; ties favor more occurrences,
     * then the shorter range. This is exploratory and can be overfit.
     */
    private fun bestWindow(p: PathStats, side: Int, total: Int): Candidate? {
        val choices = ArrayList<Candidate>()
        for (width in 2..5) {
            for (low in LAST_MINUTE_LEFT..(FIRST_MINUTE_LEFT - width + 1)) {
                val high = low + width - 1
                val s = p.window(high, low)
                if (!eligible(s.count, total)) continue
                if (abs(s.winPct(side) - p.path.second) + 1e-9 <
                    MIN_RAW_GAP_POINTS) continue
                choices.add(Candidate(p, side, high, low, s))
            }
        }
        return choices.maxWithOrNull(
            compareBy<Candidate> { it.stats.winPct(it.side) }
                .thenBy { it.hitCount }
                .thenBy { -(it.highMinute - it.lowMinute) }
        )
    }

    private fun pct(value: Double) =
        String.format(Locale.US, "%.1f", value)

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No settled BTC markets available for analysis."
        val ordered = markets.distinctBy { it.ticker }
            .sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }
                .thenBy { it.ticker })
        val stats = paths.associateWith { PathStats(it) }
        var validQuotes = 0
        for ((index, market) in ordered.withIndex()) {
            val quotes = market.quotes.filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }
            validQuotes += quotes.size
            if (quotes.size < 2) continue
            for (path in paths) {
                val completion = firstWindowCompletion(quotes, path) ?: continue
                if (completion.entryAsk(1) !in 0.01..0.99 ||
                    completion.entryAsk(-1) !in 0.01..0.99) continue
                stats.getValue(path).add(index, completion, market.yesWon)
            }
        }

        // Both sides are tested but a mirrored side is not another hit.
        // Show only the better price-adjusted BUY side for each unique path.
        val candidates = stats.values.mapNotNull { p ->
            listOfNotNull(
                bestWindow(p, 1, ordered.size),
                bestWindow(p, -1, ordered.size)
            ).maxWithOrNull(
                compareBy<Candidate> { it.roi }
                    .thenBy { it.hitCount }
                    .thenBy { it.stats.winPct(it.side) }
            )
        }.sortedWith(
            compareByDescending<Candidate> { it.roi }
                .thenByDescending { it.hitCount }
                .thenByDescending { it.gapPoints }
                .thenBy { it.p.path.first }
                .thenBy { it.p.path.second }
        )

        val oldest = Instant.ofEpochSecond(ordered.first().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        val newest = Instant.ofEpochSecond(ordered.last().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        val minFloor = max(MIN_ABSOLUTE_MATCHES,
            floor(ordered.size * MIN_MARKET_FRACTION).toInt() + 1)

        val out = StringBuilder(9000)
        out.appendLine("=== BTC TWO-POINT PATTERNS: 20-POINT GAP ===")
        out.appendLine("Scanned " + ordered.size + " DISTINCT settled 15-minute markets" +
            " | skipped loads " + skipped + " | valid quote closes " + validQuotes)
        out.appendLine("Eastern dates: " + oldest + " to " + newest)
        if (ordered.size != 1000)
            out.appendLine("INCOMPLETE SCAN: fewer than 1,000 markets.")
        out.appendLine("Minimum hit rate: ABOVE 10% of all markets AND at least 100" +
            " (minimum here: " + minFloor + " distinct markets).")
        out.appendLine("Minimum absolute gap: 20 percentage points between" +
            " BUY-side historical win rate and LAST YES price percentage.")
        out.appendLine("Time windows: consecutive minutes within 8 to 4 minutes LEFT;" +
            " only first fresh completion per path per market.")
        out.appendLine("Best timeframe = highest historical BUY-side win rate among" +
            " windows still above the hit-rate threshold.")
        out.appendLine("UP = buy YES | DOWN = buy NO. Paths use Kalshi YES midpoints.")
        out.appendLine("Ranked by historical ROI using the observed BUY ask plus" +
            " estimated taker fee, not simply by win rate.")
        out.appendLine()
        out.appendLine("MATCHING PATTERNS: " + candidates.size)
        out.appendLine()

        if (candidates.isEmpty()) {
            out.appendLine("NONE MATCHED BOTH: >10% hit rate and >=20-point raw gap.")
            out.appendLine("No lower-frequency pattern has been promoted.")
        }

        for ((index, c) in candidates.withIndex()) {
            val side = if (c.side == 1) "UP" else "DOWN"
            out.appendLine((index + 1).toString() + ") " +
                c.p.path.first + "% - " + c.p.path.second + "%")
            out.appendLine("Buy: " + side)
            out.appendLine("WR: " + pct(c.stats.winPct(c.side)) + "%" +
                " (" + c.stats.wins(c.side) + "/" + c.hitCount + ")")
            out.appendLine("Hit rate: " +
                pct(100.0 * c.hitCount / ordered.size) + "%" +
                " (" + c.hitCount + "/" + ordered.size + ")")
            out.appendLine("Best timeframe: " + c.highMinute + " to " +
                c.lowMinute + " mins left")
            out.appendLine()
        }

        out.appendLine("NOTE: the >=20-point comparison is NOT the actual trading edge.")
        out.appendLine("For DOWN, the YES ending percentage is inverted for the NO")
        out.appendLine("contract; e.g. YES 40% implies roughly NO 60%, BEFORE spread/fees.")
        out.appendLine("A pattern can pass this filter and still lose money.")
        out.appendLine("Best timing is selected retrospectively, not proven on future markets.")
        out.appendLine("Historical 1-minute closes do not guarantee fillable asks.")
        out.appendLine("No live orders or alerts were changed. END REPORT")
        return out.toString()
    }
}
