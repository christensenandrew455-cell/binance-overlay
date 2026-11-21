package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Exhaustively tests 10 signed BTC chart bands x 10 destination bands x 14
 * elapsed-minute gaps = 1,400 ordered pattern/delay rules.
 *
 * A chart band is a signed distance-to-target INDEX computed by
 * BtcChartProxyMath from already-completed Binance.US 1m candles.
 * It is not a Kalshi order-book quote, executable ask, or calibrated odds.
 * This is research-only. No trades or alert rules are changed.
 */
internal object BtcExhaustivePatternReport {
    internal val levels = listOf(-90, -80, -70, -60, -50, 50, 60, 70, 80, 90)
    internal data class Path(val from: Int, val to: Int, val gap: Int)
    internal data class Timed(val path: Path, val firstLeft: Int, val finishLeft: Int)
    internal data class Event(
        val marketIndex: Int, val firstLeft: Int, val finishLeft: Int, val up: Boolean
    )
    internal data class Scan(
        val byPath: Map<Path, List<Event>>,
        val byMinute: Map<Timed, List<Event>>
    )

    // Exact 10-point observed bands: +70 = [70,80), -70 = (-80,-70].
    // A price at the target has no signed side and is excluded, not fabricated.
    internal fun band(value: Double): Int? {
        if (!value.isFinite() || value == 0.0 || abs(value) < 50.0) return null
        val bucket = ((abs(value).toInt() / 10) * 10).coerceIn(50, 90)
        return if (value > 0) bucket else -bucket
    }

    /** A match is known only after the destination candle has fully closed. */
    internal fun analyze(markets: List<BinanceTwentySevenPatternReport.Market>): Scan {
        val paths = HashMap<Path, MutableList<Event>>()
        val times = HashMap<Timed, MutableList<Event>>()
        for ((index, market) in markets.withIndex()) {
            val points = market.minutes.asSequence()
                .filter { it.minute in 1..14 }
                .sortedBy { it.minute }
                .distinctBy { it.minute }
                .mapNotNull { observation ->
                    val level = band(observation.signedChartLevel)
                    if (level == null) null else observation.minute to level
                }.toList()
            val minuteMap = points.toMap()
            val seenPaths = HashSet<Path>()
            for ((endMinute, endBand) in points) {
                for (gap in 1..14) {
                    val firstMinute = endMinute - gap
                    val firstBand = minuteMap[firstMinute] ?: continue
                    val path = Path(firstBand, endBand, gap)
                    val event = Event(index, 15 - firstMinute, 15 - endMinute, market.upWon)
                    // Timed rows count at most once per market per EXACT time pair.
                    times.getOrPut(Timed(path, event.firstLeft, event.finishLeft)) {
                        ArrayList()
                    }.add(event)
                    // Overall path/delay frequency counts a market just once,
                    // choosing its earliest completion for this specific rule.
                    if (seenPaths.add(path)) {
                        paths.getOrPut(path) { ArrayList() }.add(event)
                    }
                }
            }
        }
        return Scan(paths, times)
    }

    private data class Count(var n: Int = 0, var up: Int = 0) {
        val down get() = n - up
        val upPct get() = if (n == 0) Double.NaN else 100.0 * up / n
        val downPct get() = if (n == 0) Double.NaN else 100.0 * down / n
        fun add(won: Boolean) { n++; if (won) up++ }
    }

    private data class Summary(val all: Count, val older: Count, val newer: Count)

    private fun summarize(events: List<Event>?, split: Int): Summary {
        val a = Count()
        val o = Count()
        val n = Count()
        for (event in events.orEmpty()) {
            a.add(event.up)
            if (event.marketIndex < split) o.add(event.up) else n.add(event.up)
        }
        return Summary(a, o, n)
    }

    // For comparison ONLY, translate a negative signed index into a 0..100
    // UP-side chart number. This is NOT an actual Kalshi contract price.
    private fun indexUp(to: Int): Double =
        if (to > 0) to.toDouble() else (100 + to).toDouble()

    private fun wilsonBounds(c: Count): Pair<Double, Double> {
        if (c.n == 0) return 0.0 to 100.0
        val z = 1.96
        val p = c.up.toDouble() / c.n
        val denom = 1.0 + z * z / c.n
        val center = (p + z * z / (2.0 * c.n)) / denom
        val margin = z * sqrt(p * (1.0 - p) / c.n +
            z * z / (4.0 * c.n * c.n)) / denom
        return 100.0 * (center - margin) to 100.0 * (center + margin)
    }

    private fun conservativeGap(s: Summary, path: Path): Double {
        val (lo, hi) = wilsonBounds(s.older)
        val reference = indexUp(path.to)
        return when {
            reference < lo -> lo - reference
            reference > hi -> reference - hi
            else -> 0.0
        }
    }

    private data class Candidate(val path: Path, val summary: Summary) {
        val rawGap get() = summary.older.upPct - indexUp(path.to)
        val conservative get() = conservativeGap(summary, path)
    }

    private fun number(v: Double): String =
        if (!v.isFinite()) "n/a" else String.format(Locale.US, "%.1f", v)

    private fun level(value: Int): String =
        (if (value > 0) "+" else "") + value.toString()

    private fun format(s: Summary, path: Path, total: Int): String =
        listOf(
            level(path.from), level(path.to), path.gap,
            s.all.n, number(if (total == 0) Double.NaN else 100.0 * s.all.n / total),
            s.all.up, s.all.down, number(s.all.upPct), number(s.all.downPct),
            number(indexUp(path.to)),
            number(s.all.upPct - indexUp(path.to)),
            s.older.n, number(s.older.upPct),
            s.newer.n, number(s.newer.upPct),
            if (s.older.n < 30) "SMALL" else "RESEARCH"
        ).joinToString(",")

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.isEmpty()) return "No complete historical BTC markets available."
        val split = (ordered.size * 7 / 10).coerceAtLeast(1)
        val scan = analyze(ordered)
        val allKeys = levels.flatMap { first ->
            levels.flatMap { last ->
                (1..14).map { gap -> Path(first, last, gap) }
            }
        }
        val summaries = allKeys.associateWith { summarize(scan.byPath[it], split) }
        val candidates = allKeys.asSequence()
            .map { Candidate(it, summaries.getValue(it)) }
            .filter { it.summary.older.n >= 30 }
            .sortedWith(compareByDescending<Candidate> { it.conservative }
                .thenByDescending { abs(it.rawGap) }
                .thenByDescending { it.summary.older.n }
                .thenBy { it.path.from }.thenBy { it.path.to }.thenBy { it.path.gap })
            .take(20).toList()

        val diagnostic = allKeys.asSequence()
            .map { Candidate(it, summaries.getValue(it)) }
            .filter { it.summary.older.n in 10..29 }
            .sortedWith(compareByDescending<Candidate> { abs(it.rawGap) }
                .thenByDescending { it.summary.older.n })
            .take(20).toList()

        val coverage = candidates.flatMap { scan.byPath[it.path].orEmpty() }
            .map { it.marketIndex }.toSet().size
        val eastern = ZoneId.of("America/New_York")
        val oldest = Instant.ofEpochMilli(ordered.first().startMs).atZone(eastern)
        val newest = Instant.ofEpochMilli(ordered.last().startMs).atZone(eastern)

        return buildString(900000) {
            appendLine("=== EXHAUSTIVE BTC 15-MINUTE TWO-READING PATTERN SCAN ===")
            appendLine("Source: Binance.US historical BTCUSDT 1-minute closed candles.")
            appendLine("Custom signed chart INDEX (not actual Kalshi YES/NO odds).")
            appendLine("Unique completed markets: " + ordered.size + "/1000")
            if (ordered.size != 1000) appendLine("INCOMPLETE: all results provisional.")
            appendLine("Eastern first start: " + oldest)
            appendLine("Eastern last start: " + newest)
            appendLine("Candidate rules: 10 starting bands x 10 ending bands x 14 delays = 1400.")
            appendLine("Levels: -90,-80,-70,-60,-50,+50,+60,+70,+80,+90.")
            appendLine("Each label is an exact signed 10-point band, not a crossed threshold.")
            appendLine("Example: +90 to +70 after 2 minutes means the +90 bin")
            appendLine("was observed at a CLOSED candle, followed by +70 at the")
            appendLine("CLOSED candle exactly two minutes later.")
            appendLine("14 one-minute closes exist (14 to 1 minutes remaining).")
            appendLine("An initial 15-min-left +90 observation is unavailable.")
            appendLine("Consequently gaps of 14 minutes have zero observed hits;")
            appendLine("the largest observed close-to-close gap is 13 minutes.")
            appendLine("Each market is counted once per path+gap in main results,")
            appendLine("at its FIRST match; different paths overlap.")
            appendLine("Exact-time rows count each market once at each timestamp")
            appendLine("and may include that market in more than one time row.")
            appendLine("UP/DOWN = final 15-minute BTC close compared with starting target.")
            appendLine("The index comparison is a research discrepancy, not a")
            appendLine("Kalshi price advantage, fill, profitability, or probability proof.")
            appendLine("Oldest " + split + " markets = discovery; newest " +
                (ordered.size - split) + " = chronological holdout.")
            appendLine("Rankings ONLY use discovery outcomes. Newest outcomes")
            appendLine("are displayed separately and were NOT used to rank.")
            appendLine("The rank uses the 95% Wilson interval's distance from")
            appendLine("the destination chart number, then raw difference and count.")
            appendLine("Minimum discovery count for ranked results = 30.")
            appendLine("Ranks are exploratory across 1,400 tests. No live alerts/orders.")
            appendLine()

            appendLine("=== TOP DISCOVERY PATTERNS (30+ OLDER MATCHES) ===")
            if (candidates.isEmpty()) {
                appendLine("No pattern has 30+ older hits. No high-sample ranked results.")
            }
            for ((rank, item) in candidates.withIndex()) {
                val p = item.path
                val s = item.summary
                appendLine((rank + 1).toString() + ". " +
                    level(p.from) + " -> " + level(p.to) + " in " + p.gap + " min" +
                    " | hit " + s.all.n + "/" + ordered.size +
                    " (" + number(100.0 * s.all.n / ordered.size) + "%)" +
                    " | overall UP " + number(s.all.upPct) + "%" +
                    " DOWN " + number(s.all.downPct) + "%" +
                    " | chart UP index " + number(indexUp(p.to)) +
                    " | older UP " + number(s.older.upPct) + "% (" +
                    s.older.n + ")" +
                    " | newer UP " + number(s.newer.upPct) + "% (" +
                    s.newer.n + ")" +
                    " | older gap " + number(item.rawGap) + "pp" +
                    " | conservative gap " + number(item.conservative) + "pp")
            }
            appendLine("Combined DISTINCT markets matching ranked patterns: " +
                coverage + "/" + ordered.size +
                " (" + number(100.0 * coverage / ordered.size) + "%).")
            appendLine("This combined coverage is not a sum of the hit rates.")
            appendLine()

            appendLine("=== LOW-SAMPLE DIAGNOSTICS (10-29 OLDER HITS) ===")
            appendLine("DO NOT promote these into trading rules without new data.")
            for ((rank, item) in diagnostic.withIndex()) {
                val s = item.summary
                val p = item.path
                appendLine((rank + 1).toString() + ". " + level(p.from) +
                    " -> " + level(p.to) + " in " + p.gap + " min" +
                    " | older " + s.older.n +
                    " | older UP " + number(s.older.upPct) + "%" +
                    " | newer " + s.newer.n +
                    " | newer UP " + number(s.newer.upPct) + "%" +
                    " | older gap " + number(item.rawGap) + "pp")
            }
            if (diagnostic.isEmpty()) appendLine("No 10-29 count patterns.")
            appendLine()

            appendLine("=== ALL 1400 PAIR+GAP COMBINATIONS (CSV) ===")
            appendLine("from_chart,to_chart,gap_minutes,hits,hit_pct_of_markets," +
                "up,down,up_pct,down_pct,destination_chart_up_index," +
                "overall_up_minus_index_pp,older_hits,older_up_pct," +
                "newer_hits,newer_up_pct,flag")
            for (key in allKeys) {
                appendLine(format(summaries.getValue(key), key, ordered.size))
            }
            appendLine()

            appendLine("=== EVERY OBSERVED EXACT START/END MINUTE (CSV) ===")
            appendLine("from_chart,to_chart,gap_minutes,first_minutes_left," +
                "finish_minutes_left,hits,hit_pct_of_markets,up,down," +
                "up_pct,down_pct,chart_up_index,up_minus_index_pp," +
                "older_hits,older_up_pct,newer_hits,newer_up_pct")
            val byTime = scan.byMinute.entries.sortedWith(
                compareBy<Map.Entry<Timed, List<Event>>> { it.key.path.from }
                    .thenBy { it.key.path.to }
                    .thenBy { it.key.path.gap }
                    .thenByDescending { it.key.firstLeft }
            )
            for ((timed, events) in byTime) {
                val s = summarize(events, split)
                appendLine(listOf(
                    level(timed.path.from), level(timed.path.to), timed.path.gap,
                    timed.firstLeft, timed.finishLeft,
                    s.all.n, number(100.0 * s.all.n / ordered.size),
                    s.all.up, s.all.down, number(s.all.upPct), number(s.all.downPct),
                    number(indexUp(timed.path.to)),
                    number(s.all.upPct - indexUp(timed.path.to)),
                    s.older.n, number(s.older.upPct),
                    s.newer.n, number(s.newer.upPct)
                ).joinToString(","))
            }
            appendLine("=== END. RESEARCH ONLY. NO ORDERS OR ALERT CHANGES. ===")
        }
    }
}
