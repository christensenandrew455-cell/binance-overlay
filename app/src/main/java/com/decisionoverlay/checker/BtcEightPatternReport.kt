package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.sqrt

/**
 * The four user-selected signed chart patterns, with the legacy class name preserved.
 * The same 1,000 Binance.US 15-minute BTC markets and closed-candle
 * signed chart index as the exhaustive scanner are used.
 *
 * Research only: outcome rates do not represent Kalshi quote prices or P&L.
 */
internal object BtcEightPatternReport {
    internal data class Rule(val path: BtcExhaustivePatternReport.Path, val buyUp: Boolean) {
        val side: String get() = if (buyUp) "UP" else "DOWN"
        val name: String get() = level(path.from) + " -> " + level(path.to) +
            " in " + path.gap + " min"
    }
    internal val rules = listOf(
        Rule(BtcExhaustivePatternReport.Path(-50, -60, 1), false),
        Rule(BtcExhaustivePatternReport.Path(-50, -60, 2), false),
        Rule(BtcExhaustivePatternReport.Path(+50, +60, 1), true),
        Rule(BtcExhaustivePatternReport.Path(+50, +60, 2), true)
    )

    private data class Counts(val hits: Int, val wins: Int) {
        val hitRate: Double get() = if (hits == 0) Double.NaN else 100.0 * wins / hits
    }
    private data class Window(
        val firstLeft: Int,
        val finishLeft: Int,
        val all: Counts,
        val older: Counts,
        val newer: Counts
    ) {
        val label get() = firstLeft.toString() + " -> " + finishLeft + " min left"
    }
    private fun level(value: Int) = (if (value > 0) "+" else "") + value
    private fun pct(numerator: Int, denominator: Int): Double =
        if (denominator == 0) Double.NaN else 100.0 * numerator / denominator
    private fun n(value: Double): String =
        if (!value.isFinite()) "N/A" else String.format(Locale.US, "%.1f", value)
    private fun counts(events: List<BtcExhaustivePatternReport.Event>, buyUp: Boolean): Counts =
        Counts(events.size, events.count { it.up == buyUp })

    /** Conservative 95% Wilson LOWER bound, used to select (not promise) a time. */
    private fun wilsonLower(c: Counts): Double {
        if (c.hits == 0) return 0.0
        val z = 1.96
        val p = c.wins.toDouble() / c.hits
        val d = 1.0 + z * z / c.hits
        return 100.0 * ((p + z * z / (2 * c.hits) -
            z * sqrt(p * (1 - p) / c.hits + z * z / (4.0 * c.hits * c.hits))) / d)
    }
    private fun formatWindow(w: Window?, totalOlder: Int, totalNewer: Int): String {
        if (w == null) return "N/A (no observed exact-minute pair)"
        return w.label + " | older hits " + w.older.hits + "/" + totalOlder +
            " (" + n(pct(w.older.hits, totalOlder)) + "%)" +
            " | older wins " + w.older.wins + "/" + w.older.hits +
            " (" + n(w.older.hitRate) + "%)" +
            " | newer hits " + w.newer.hits + "/" + totalNewer +
            " | newer win " + n(w.newer.hitRate) + "%"
    }

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.isEmpty()) return "No complete historical BTC markets available."
        val total = ordered.size
        val split = (total * 7 / 10).coerceAtLeast(1)
        val recent = total - split
        val scan = BtcExhaustivePatternReport.analyze(ordered)
        val zone = ZoneId.of("America/New_York")
        fun timestamp(value: Long) = Instant.ofEpochMilli(value).atZone(zone).toString()
        val exactRows = ArrayList<String>()

        return buildString {
            appendLine("=== FOUR SELECTED BTC PATTERNS: 1,000 MARKET TEST ===")
            appendLine("Completed BTC markets: " + total + "/1000")
            if (total != 1000) appendLine("INCOMPLETE: all figures provisional.")
            appendLine("Earliest market: " + timestamp(ordered.first().startMs))
            appendLine("Latest market: " + timestamp(ordered.last().startMs))
            appendLine("Source: Binance.US BTCUSDT closed 1-minute candles; 15-minute settlements.")
            appendLine("Chart levels are signed 10-point INDEX bands, NOT Kalshi prices.")
            appendLine("Minus = below BTC target; plus = above BTC target. The +/-50")
            appendLine("bands are separate internal bins though the visible chart has one 50.")
            appendLine("A 1m or 2m gap means elapsed time BETWEEN candle CLOSES,")
            appendLine("not time left until settlement. 14 -> 13 is an exact time pair.")
            appendLine("Side for wins: negative destination = DOWN, positive = UP.")
            appendLine("Hit rate = distinct markets matching this path / " + total + ".")
            appendLine("Win rate = matching markets that settled on selected side / hits.")
            appendLine("Count each path once per market at EARLIEST completion.")
            appendLine("Exact-minute windows can overlap WITHIN the same market.")
            appendLine("Oldest " + split + " markets select best times; newest " + recent +
                " are holdout and are NEVER used to choose the window.")
            appendLine("Most frequent time = most older-market matches.")
            appendLine("Best supported win time = highest 95% Wilson lower win bound")
            appendLine("among exact pairs with >=20 discovery hits, not highest")
            appendLine("100%-from-one-example win rate. Insufficient data = N/A.")
            appendLine("Outcome win rates are NOT simulated trades, real fills, or profits.")
            appendLine("Only the four selected patterns, each with >=20% overall hit rate.")
            appendLine("Historical win rate is not an actual trade win rate. RESEARCH ONLY.")
            appendLine()

            for ((index, rule) in rules.withIndex()) {
                val events = scan.byPath[rule.path].orEmpty()
                val all = counts(events, rule.buyUp)
                val old = counts(events.filter { it.marketIndex < split }, rule.buyUp)
                val new = counts(events.filter { it.marketIndex >= split }, rule.buyUp)
                val windows = scan.byMinute.entries.asSequence()
                    .filter { it.key.path == rule.path }
                    .map { (t, ev) ->
                        Window(t.firstLeft, t.finishLeft,
                            counts(ev, rule.buyUp),
                            counts(ev.filter { it.marketIndex < split }, rule.buyUp),
                            counts(ev.filter { it.marketIndex >= split }, rule.buyUp))
                    }.sortedByDescending { it.firstLeft }.toList()
                val frequent = windows.maxWithOrNull(
                    compareBy<Window> { it.older.hits }
                        .thenBy { it.all.hits }.thenBy { it.firstLeft })
                val bestWin = windows.filter { it.older.hits >= 20 }.maxWithOrNull(
                    compareBy<Window> { wilsonLower(it.older) }
                        .thenBy { it.older.hits }.thenBy { it.firstLeft })
                appendLine((index + 1).toString() + ". " + rule.name +
                    " | BUY " + rule.side +
                    " | hit " + all.hits + "/" + total +
                    " (" + n(pct(all.hits, total)) + "%)" +
                    " | win " + all.wins + "/" + all.hits +
                    " (" + n(all.hitRate) + "%)")
                appendLine("   Oldest: " + old.hits + " hits, " +
                    n(old.hitRate) + "% win | Newest: " + new.hits +
                    " hits, " + n(new.hitRate) + "% win")
                appendLine("   Most frequent time: " + formatWindow(frequent, split, recent))
                appendLine("   Best supported win time: " +
                    if (bestWin == null) "N/A (fewer than 20 older matches per exact window)"
                    else formatWindow(bestWin, split, recent) +
                        " | discovery lower bound " + n(wilsonLower(bestWin.older)) + "%"
                )
                appendLine()
                for (w in windows) {
                    exactRows.add(listOf(
                        level(rule.path.from), level(rule.path.to), rule.path.gap, rule.side,
                        w.firstLeft, w.finishLeft, w.all.hits,
                        n(pct(w.all.hits, total)), w.all.wins, n(w.all.hitRate),
                        w.older.hits, w.older.wins, n(w.older.hitRate),
                        w.newer.hits, w.newer.wins, n(w.newer.hitRate),
                        if (w.older.hits >= 20) n(wilsonLower(w.older)) else "N/A"
                    ).joinToString(","))
                }
            }
            appendLine("=== ALL OBSERVED EXACT-TIME WINDOWS (CSV) ===")
            appendLine("from_chart,to_chart,gap_minutes,buy_side,first_minutes_left," +
                "finish_minutes_left,hits,hit_pct_of_markets,wins,win_pct," +
                "older_hits,older_wins,older_win_pct,newer_hits,newer_wins," +
                "newer_win_pct,older_wilson_lower_95_pct")
            exactRows.forEach { appendLine(it) }

            appendLine()
            appendLine("=== GRAPH-READY FOUR-PATTERN WIN RATES BY FINISH MINUTE (CSV) ===")
            appendLine("All finish minutes 14 down to 1 are included; no matches = N/A, not 0%.")
            appendLine("Each market can match different minutes but only once per exact pattern/time.")
            appendLine("The chart percentage index is NOT Kalshi odds or an executable price.")
            appendLine("No win-rate cutoff or notification change is applied here.")
            appendLine("pattern,from_chart,to_chart,gap_minutes,side,first_minutes_left," +
                "minutes_left,hits,wins,win_pct,older_hits,older_wins,older_win_pct," +
                "newer_hits,newer_wins,newer_win_pct,older_wilson_lower_95_pct")
            for (rule in rules) for (minuteLeft in 14 downTo 1) {
                val firstLeft = minuteLeft + rule.path.gap
                val events = if (firstLeft <= 14)
                    scan.byMinute[BtcExhaustivePatternReport.Timed(
                        rule.path, firstLeft, minuteLeft)].orEmpty()
                else emptyList()
                val all = counts(events, rule.buyUp)
                val old = counts(events.filter { it.marketIndex < split }, rule.buyUp)
                val new = counts(events.filter { it.marketIndex >= split }, rule.buyUp)
                appendLine(listOf(
                    rule.name, level(rule.path.from), level(rule.path.to),
                    rule.path.gap, rule.side,
                    if (firstLeft <= 14) firstLeft.toString() else "N/A",
                    minuteLeft, all.hits, all.wins, n(all.hitRate),
                    old.hits, old.wins, n(old.hitRate),
                    new.hits, new.wins, n(new.hitRate),
                    if (old.hits >= 20) n(wilsonLower(old)) else "N/A"
                ).joinToString(","))
            }
            appendLine("=== END: FOUR SELECTED BTC PATTERNS. RESEARCH ONLY. ===")
        }
    }
}
