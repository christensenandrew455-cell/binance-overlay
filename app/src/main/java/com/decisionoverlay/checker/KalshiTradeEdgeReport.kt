package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Research-only search for mispriced YES/NO patterns in settled Kalshi markets.
 * Paths use observed YES quote MIDPOINT percentages, not the Binance chart.
 * BUY price always comes from the observed ASK for the contract being bought.
 */
object KalshiTradeEdgeReport {
    private const val MIN_EDGE_POINTS = 20.0
    private const val MIN_HIT_FRACTION = 0.10
    private const val MIN_DISCOVERY_SAMPLES = 30
    private val levels = (10..90 step 10).toList()

    internal data class Window(val high: Int, val low: Int) {
        fun label() = high.toString() + " to " + low + " mins left"
        fun includes(left: Int) = left in low..high
    }

    private val windows = listOf(
        Window(12, 1), Window(12, 9), Window(12, 6),
        Window(8, 6), Window(8, 4), Window(5, 3),
        Window(5, 1), Window(2, 1)
    )

    private val paths: List<List<Int>> = buildList {
        for (a in levels) for (b in levels) {
            if (a == b) continue
            add(listOf(a, b))
            for (c in levels) if (b != c) add(listOf(a, b, c))
        }
    }

    internal data class Event(
        val index: Int, val quote: KalshiPatternMath.Quote, val yesWon: Boolean
    )

    /**
     * Thresholds must occur in order, on DIFFERENT completed minute closes.
     * After a completion in the research-only first three minutes, re-arm for
     * a fresh sequence rather than counting the old completion as a trade.
     * This method never invents an entry at an exact threshold that was skipped.
     */
    internal fun firstTradeCompletion(
        sortedQuotes: List<KalshiPatternMath.Quote>, path: List<Int>
    ): KalshiPatternMath.Quote? {
        if (path.size !in 2..3 || path.zipWithNext().any { it.first == it.second })
            return null
        var stage = 0
        var priorStageLeft = 16
        for (q in sortedQuotes) {
            if (!q.valid()) continue
            // A stage must actually be observed in its 10-point midpoint
            // bucket. A jump over 60 is NOT evidence of a 60% quote close.
            val mid = q.strength(1)
            if (stage == 0) {
                if (kotlin.math.abs(mid - path[0]) < 5.0) {
                    stage = 1
                    priorStageLeft = q.left
                }
                continue
            }
            if (kotlin.math.abs(mid - path[stage]) >= 5.0 ||
                q.left >= priorStageLeft) continue
            priorStageLeft = q.left
            stage++
            if (stage == path.size) {
                stage = 0
                if (q.left in 1..12) return q
            }
        }
        return null
    }

    internal class Stats {
        var count = 0
        var wins = 0
        var askSum = 0.0
        var feeSum = 0.0
        var netSum = 0.0
        val marketIndexes = HashSet<Int>()

        fun add(event: Event, side: Int) {
            val ask = event.quote.entryAsk(side)
            if (ask !in 0.01..0.99 || !marketIndexes.add(event.index)) return
            val fee = KalshiPatternMath.approximateFee(ask)
            val won = if (side == 1) event.yesWon else !event.yesWon
            count++
            if (won) wins++
            askSum += ask
            feeSum += fee
            netSum += (if (won) 1.0 else 0.0) - ask - fee
        }

        fun winPct() = if (count == 0) 0.0 else 100.0 * wins / count
        fun askCents() = if (count == 0) 0.0 else 100.0 * askSum / count
        fun feeCents() = if (count == 0) 0.0 else 100.0 * feeSum / count
        fun netCents() = if (count == 0) 0.0 else 100.0 * netSum / count
        fun edgePoints() = winPct() - askCents()
        fun afterFeeEdgePoints() = edgePoints() - feeCents()
        fun roiPct() = if (askSum + feeSum > 0)
            100.0 * netSum / (askSum + feeSum) else 0.0
        fun hitPct(total: Int) = if (total == 0) 0.0 else 100.0 * count / total
    }

    internal fun score(
        events: List<Event>, side: Int, window: Window, split: Int
    ): Triple<Stats, Stats, Stats> {
        val discovery = Stats()
        val holdout = Stats()
        val all = Stats()
        for (event in events) {
            if (!window.includes(event.quote.left)) continue
            all.add(event, side)
            if (event.index < split) discovery.add(event, side)
            else holdout.add(event, side)
        }
        return Triple(discovery, holdout, all)
    }

    private data class Candidate(
        val path: List<Int>, val side: Int, val window: Window,
        val discovery: Stats, val holdout: Stats, val all: Stats,
        val discoveryTotal: Int
    ) {
        val expectedCentsPerMarket: Double
            get() = 100.0 * discovery.netSum / discoveryTotal
        fun key() = path.joinToString(",") + ":" + side
        fun mirrorKey() = path.map { 100 - it }.joinToString(",") + ":" + (-side)
    }

    private fun fmt(x: Double) = String.format(Locale.US, "%.1f", x)
    private fun signed(x: Double) = String.format(Locale.US, "%+.1f", x)
    private fun sideName(side: Int) = if (side == 1) "UP (YES)" else "DOWN (NO)"
    private fun pathName(path: List<Int>) =
        "YES " + path.joinToString("% -> ", postfix = "%")
    private fun describe(s: Stats, total: Int): String =
        "n=" + s.count + "/" + total +
        " hit=" + fmt(s.hitPct(total)) + "%" +
        " WR=" + (if (s.count == 0) "n/a" else fmt(s.winPct()) + "%") +
        " ask=" + (if (s.count == 0) "n/a" else fmt(s.askCents()) + "c") +
        " fee=" + (if (s.count == 0) "n/a" else fmt(s.feeCents()) + "c") +
        " edge=" + (if (s.count == 0) "n/a" else signed(s.edgePoints()) + "pp") +
        " net=" + (if (s.count == 0) "n/a" else signed(s.netCents()) + "c/contract")

    /**
     * The chronological 70% discovery group chooses paths and time windows.
     * The newest 30% is shown separately and NEVER used to tune win-rate edges.
     * The whole-sample hit floor is enforced because the user requires frequent
     * patterns; consequently the newest portion is not a pristine holdout for
     * frequency claims.
     */
    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No settled BTC markets available for analysis."
        val ordered = markets.distinctBy { it.ticker }
            .sortedWith(compareBy<KalshiPatternMath.Market> { it.closeTs }
                .thenBy { it.ticker })
        if (ordered.size < 2) return "Too few distinct settled markets to backtest."
        val split = (ordered.size * 7 / 10).coerceIn(1, ordered.lastIndex)
        val prepared = ordered.map { market ->
            market.quotes.filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }
        }
        val eventsByPath = HashMap<List<Int>, List<Event>>(paths.size)
        for (path in paths) {
            val events = ArrayList<Event>()
            for ((index, quotes) in prepared.withIndex()) {
                if (quotes.size < path.size) continue
                val completion = firstTradeCompletion(quotes, path) ?: continue
                events.add(Event(index, completion, ordered[index].yesWon))
            }
            eventsByPath[path] = events
        }

        val candidates = ArrayList<Candidate>()
        for (path in paths) {
            val events = eventsByPath.getValue(path)
            for (side in listOf(1, -1)) {
                var best: Candidate? = null
                for (window in windows) {
                    val (discovery, holdout, all) = score(events, side, window, split)
                    // At least 20 percentage points BETTER, not absolute distance.
                    // Compare to the observed ASK for the SAME contract side.
                    if (discovery.count < MIN_DISCOVERY_SAMPLES ||
                        discovery.hitPct(split) <= 100.0 * MIN_HIT_FRACTION ||
                        all.hitPct(ordered.size) <= 100.0 * MIN_HIT_FRACTION ||
                        discovery.edgePoints() + 1e-9 < MIN_EDGE_POINTS ||
                        discovery.netCents() <= 0.0) continue
                    val candidate = Candidate(path, side, window,
                        discovery, holdout, all, split)
                    if (best == null ||
                        candidate.expectedCentsPerMarket > best.expectedCentsPerMarket + 1e-9 ||
                        (kotlin.math.abs(candidate.expectedCentsPerMarket -
                            best.expectedCentsPerMarket) < 1e-9 &&
                            candidate.discovery.count > best.discovery.count)) {
                        best = candidate
                    }
                }
                if (best != null) candidates.add(best)
            }
        }
        val candidateKeys = candidates.map { it.key() }.toHashSet()
        val paired = candidates.filter { it.mirrorKey() in candidateKeys }
        val unpaired = candidates.filter { it.mirrorKey() !in candidateKeys }
        val rank = compareByDescending<Candidate> { it.expectedCentsPerMarket }
            .thenByDescending { it.discovery.count }
            .thenByDescending { it.discovery.roiPct() }
            .thenBy { it.path.joinToString(",") }
        val ranked = paired.sortedWith(rank) + unpaired.sortedWith(rank)

        val coverageAll = HashSet<Int>()
        val coverageDiscovery = HashSet<Int>()
        val coverageHoldout = HashSet<Int>()
        val pairedCoverage = HashSet<Int>()
        var upCount = 0
        var downCount = 0
        for (c in candidates) {
            if (c.side == 1) upCount++ else downCount++
            coverageAll.addAll(c.all.marketIndexes)
            coverageDiscovery.addAll(c.discovery.marketIndexes)
            coverageHoldout.addAll(c.holdout.marketIndexes)
            if (c.mirrorKey() in candidateKeys) pairedCoverage.addAll(c.all.marketIndexes)
        }

        val oldest = Instant.ofEpochSecond(ordered.first().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        val newest = Instant.ofEpochSecond(ordered.last().closeTs)
            .atZone(ZoneId.of("America/New_York")).toLocalDate()
        val out = StringBuilder(50000)
        out.appendLine("=== KALSHI BTC ACTUAL-PRICE EDGE SCANNER: 2 + 3 POINTS ===")
        out.appendLine("Settled distinct markets=" + ordered.size +
            " | skipped=" + skipped + " | Eastern=" + oldest + " through " + newest)
        if (ordered.size < 1000)
            out.appendLine("INCOMPLETE: fewer than 1,000 historical markets loaded.")
        out.appendLine("Discovery: oldest " + split + " | newest holdout: " +
            (ordered.size - split) + " markets.")
        out.appendLine("Scanned " + paths.size + " unique YES midpoint paths, " +
            "both buy sides, across " + windows.size + " preset windows.")
        out.appendLine("Stages in observed +/-5pp YES midpoint buckets on separate VALID minute closes (skipped levels do not count). 15-13 minutes " +
            "left are research only; entry allowed 12-1 minutes left.")
        out.appendLine("BUY UP=YES ask; BUY DOWN=NO ask (1 minus YES bid). " +
            "Labels show YES quote midpoints; NOT Binance chart percentages.")
        out.appendLine("QUALIFY: historical BUY-side win rate minus actual BUY-side " +
            "mean observed ask >=20 percentage points, positive fee-adjusted net, " +
            "and hit rate STRICTLY ABOVE 10% in discovery AND overall.")
        out.appendLine("At least " + MIN_DISCOVERY_SAMPLES + " older examples required. " +
            "Quotes have spreads <=25c; fees are estimates, fills not guaranteed.")
        out.appendLine("Best timeframe selected on discovery by expected net cents " +
            "per observed market, favoring more opportunities.")
        out.appendLine("Paired means a separately qualifying mirrored YES path " +
            "(100 minus each stage) with the opposite BUY side.")
        out.appendLine("Newest outcomes do not tune win-rate selection, but the overall " +
            "frequency filter uses newest market counts. This is NOT a pristine " +
            "independent validation; multiple-pattern search can overfit.")
        out.appendLine()
        out.appendLine("QUALIFYING BUY UP=" + upCount + " | BUY DOWN=" + downCount +
            " | total=" + candidates.size + " | matched mirror=" + paired.size +
            " | unmatched=" + unpaired.size)
        out.appendLine("Combined distinct-market hit: " + coverageAll.size + "/" +
            ordered.size + " (" + fmt(100.0 * coverageAll.size / ordered.size) + "%)")
        out.appendLine("Discovery coverage: " + coverageDiscovery.size + "/" + split +
            " (" + fmt(100.0 * coverageDiscovery.size / split) + "%)")
        out.appendLine("Newest coverage: " + coverageHoldout.size + "/" +
            (ordered.size - split) + " (" + fmt(100.0 * coverageHoldout.size /
                (ordered.size - split)) + "%)")
        out.appendLine("Mirror-paired coverage: " + pairedCoverage.size + "/" +
            ordered.size + " (" + fmt(100.0 * pairedCoverage.size / ordered.size) + "%)")
        out.appendLine("Coverage counts distinct markets ONCE, not overlapping signals.")
        out.appendLine("50% combined market coverage is a GOAL, never a forced result.")
        out.appendLine()
        if (ranked.isEmpty()) {
            out.appendLine("NO PATTERNS CLEARED THE REAL BUY-PRICE EDGE + " +
                "FREQUENCY FILTERS. No hypothetical trades qualified.")
        }

        for ((index, c) in ranked.withIndex()) {
            val isPaired = c.mirrorKey() in candidateKeys
            if (index == 0) out.appendLine("=== PAIRED FIRST; THEN UNPAIRED ===")
            if (index == paired.size && unpaired.isNotEmpty())
                out.appendLine("=== UNPAIRED: MIRROR DID NOT QUALIFY ===")
            out.appendLine((index + 1).toString() + ") " +
                (if (c.path.size == 2) "2-POINT" else "3-POINT") +
                " | " + pathName(c.path) + " | BUY " + sideName(c.side))
            out.appendLine("  Mirror: " + pathName(c.path.map { 100 - it }) +
                " | BUY " + sideName(-c.side) +
                " | " + (if (isPaired) "QUALIFIED SEPARATELY" else "NOT QUALIFIED"))
            out.appendLine("  Best timeframe: " + c.window.label())
            out.appendLine("  Discovery: " + describe(c.discovery, split))
            out.appendLine("  Newest:    " + describe(c.holdout, ordered.size - split))
            out.appendLine("  Overall:   " + describe(c.all, ordered.size))
            out.appendLine("  Est. discovery ROI: " + signed(c.discovery.roiPct()) +
                "% | weighted net: " + signed(c.expectedCentsPerMarket) +
                " cents per market scanned")
            if (c.path.size == 3) {
                val parent = c.path.take(2)
                val (parentOlder, _, _) = score(
                    eventsByPath.getValue(parent), c.side, c.window, split)
                out.appendLine("  SAME-WINDOW 2-point " + pathName(parent) +
                    ": hit=" + fmt(parentOlder.hitPct(split)) +
                    "% WR=" + (if (parentOlder.count == 0) "n/a"
                        else fmt(parentOlder.winPct()) + "%") +
                    " (compare extra stage's frequency/edge)")
            }
            val newestConfirmed = c.holdout.count >= 30 &&
                c.holdout.edgePoints() >= MIN_EDGE_POINTS &&
                c.holdout.netCents() > 0.0
            out.appendLine("  Newest check: " + (if (newestConfirmed)
                "PASSES exploratory thresholds (not proof)"
                else "UNCONFIRMED: do not assume an edge"))
            out.appendLine()
        }
        out.appendLine("END RESEARCH. This scan does not alter alerts or place orders.")
        out.appendLine("Do not trade a result without forward testing with " +
            "live executable asks, spreads, and actual fees.")
        return out.toString()
    }
}
