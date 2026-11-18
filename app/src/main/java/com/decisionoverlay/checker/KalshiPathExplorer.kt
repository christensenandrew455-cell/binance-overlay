package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.floor

/**
 * Conditional, path-dependent outcome tables from REAL historical Kalshi quote closes.
 * Every key counts a market at most once. A level crossed between snapshots does NOT
 * imply an execution at that level: observed asks are used for hypothetical entries.
 */
object KalshiPathExplorer {
    private val highs = listOf(60, 70, 80, 90)
    private val sides = listOf(1, -1)
    private const val SMALL_SAMPLE = 30

    private data class Point(val left: Int, val strength: Double, val ask: Double)
    private data class Signal(
        val ticker: String, val side: Int, val high: Int, val low: Int,
        val recovery: Int, val firstLeft: Int, val lowLeft: Int, val left: Int,
        val won: Boolean, val ask: Double
    )
    private class Stats {
        var n = 0
        var wins = 0
        var ask = 0.0
        var fees = 0.0
        var net = 0.0
        var holdoutN = 0
        var holdoutWins = 0
        fun add(s: Signal, holdout: Boolean) {
            n++
            if (s.won) wins++
            ask += s.ask
            val fee = KalshiPatternMath.approximateFee(s.ask)
            fees += fee
            net += (if (s.won) 1.0 else 0.0) - s.ask - fee
            if (holdout) {
                holdoutN++
                if (s.won) holdoutWins++
            }
        }
        fun csv() = "$n,$wins,${pct(wins, n)},${num(100 * ask / n)}," +
            "${num(100 * fees / n)},${num(100 * net / n)},$holdoutN," +
            "$holdoutWins,${pct(holdoutWins, holdoutN)},${if (n < SMALL_SAMPLE) "SMALL" else "OK"}"
    }

    private fun num(d: Double) = String.format(Locale.US, "%.2f", d)
    private fun pct(wins: Int, n: Int) = if (n == 0) "n/a"
        else String.format(Locale.US, "%.1f%%", 100.0 * wins / n)
    private fun side(s: Int) = if (s == 1) "YES" else "NO"

    private fun appendTable(
        out: StringBuilder, title: String, dimensions: String,
        signals: List<Signal>, holdout: Set<String>,
        key: (Signal) -> String, allKeys: List<String>? = null
    ) {
        out.appendLine(title)
        out.appendLine("$dimensions,n,wins,win%,mean_actual_ask_cents,mean_estimated_fee_cents," +
            "mean_estimated_net_cents,holdout_n,holdout_wins,holdout_win%,sample_flag")
        val aggregates = LinkedHashMap<String, Stats>()
        for (s in signals) {
            aggregates.getOrPut(key(s)) { Stats() }.add(s, s.ticker in holdout)
        }
        val keys = allKeys ?: aggregates.keys.sorted()
        for (name in keys) {
            val v = aggregates[name]
            out.appendLine("$name,${v?.csv() ?: "0,0,n/a,n/a,n/a,n/a,0,0,n/a,SMALL"}")
        }
        out.appendLine()
    }

    fun report(markets: List<KalshiPatternMath.Market>): String {
        if (markets.isEmpty()) return "No valid markets to analyze."
        val chronological = markets.sortedBy { it.closeTs }
        val holdout = chronological.drop(chronological.size * 7 / 10)
            .map { it.ticker }.toSet()
        val snapshots = ArrayList<Signal>()
        val firstHits = ArrayList<Signal>()
        val drops = ArrayList<Signal>()
        val recoveries = ArrayList<Signal>()

        for (market in chronological) {
            val quotes = market.quotes.filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }
            for (direction in sides) {
                val won = if (direction == 1) market.yesWon else !market.yesWon
                val points = quotes.map {
                    Point(it.left, it.strength(direction), it.entryAsk(direction))
                }
                for (p in points) {
                    val bucket = (floor(p.strength / 10.0).toInt() * 10)
                        .coerceIn(0, 100)
                    snapshots.add(Signal(market.ticker, direction, 0, bucket, 0,
                        0, 0, p.left, won, p.ask))
                }
                for (high in highs) {
                    val atHigh = points.indexOfFirst { it.strength + 1e-8 >= high }
                    if (atHigh < 0) continue
                    val first = points[atHigh]
                    firstHits.add(Signal(market.ticker, direction, high, 0, 0,
                        first.left, 0, first.left, won, first.ask))
                    for (low in 10 until high step 10) {
                        val atLow = ((atHigh + 1) until points.size)
                            .firstOrNull { points[it].strength <= low + 1e-8 }
                            ?: continue
                        val down = points[atLow]
                        drops.add(Signal(market.ticker, direction, high, low, 0,
                            first.left, down.left, down.left, won, down.ask))
                        for (recovery in (low + 10)..high step 10) {
                            val atRecovery = ((atLow + 1) until points.size)
                                .firstOrNull { points[it].strength + 1e-8 >= recovery }
                                ?: continue
                            val up = points[atRecovery]
                            recoveries.add(Signal(market.ticker, direction,
                                high, low, recovery, first.left, down.left,
                                up.left, won, up.ask))
                        }
                    }
                }
            }
        }
        val out = StringBuilder(60000)
        out.appendLine("\n=== COMPLETE MINUTE-BY-MINUTE PATH BACKTEST ===")
        out.appendLine("${chronological.size} unique observed BTC 15-minute Kalshi markets; YES/NO separately")
        out.appendLine("Every percentage below is an EMPIRICAL SETTLEMENT WIN RATE, not the prior quoted odds.")
        out.appendLine("Each event is counted once per market/side/rule; rows overlap, do not add n across rules.")
        out.appendLine("15 to 1 = whole minutes remaining at quote candle close; 0 = settlement only, no entry.")
        out.appendLine("No intraminute price movement, fills or missed thresholds can be reconstructed.")
        out.appendLine("All entries use the OBSERVED ASK of the triggering minute, not the named threshold.")
        out.appendLine("Estimated net = settlement payout minus observed ask minus approximate taker fee.")
        out.appendLine("First 3 minutes (15-13 left) are research only, not eligible trade entries.")
        out.appendLine("Newest 30% shown as chronological holdout; comparing many rules to it still risks selection bias.")
        out.appendLine("n<30 marked SMALL, and even n>=30 is not proof of an edge.")
        out.appendLine("Eastern session filter (10:00-23:00) and download count are applied before analysis.")
        out.appendLine()

        appendTable(out, "1) HIGH -> LOW: ALL RETRACEMENT PAIRS", "side,first_high,drop_to",
            drops, holdout, { "${side(it.side)},${it.high},${it.low}" },
            sides.flatMap { s -> highs.flatMap { h ->
                (10 until h step 10).map { low -> "${side(s)},$h,$low" }
            } })
        appendTable(out, "2) HIGH -> LOW AT EACH EXACT MINUTE (15 TO 0)",
            "side,first_high,drop_to,minute_left", drops, holdout,
            { "${side(it.side)},${it.high},${it.low},${it.left}" },
            sides.flatMap { s -> highs.flatMap { h ->
                (10 until h step 10).flatMap { low ->
                    (15 downTo 0).map { minute -> "${side(s)},$h,$low,$minute" }
                }
            } })
        appendTable(out, "3) HIGH -> LOW BY BOTH FIRST-HIT AND DROP MINUTES (OBSERVED ROWS)",
            "side,first_high,drop_to,first_high_minute_left,drop_minute_left",
            drops, holdout,
            { "${side(it.side)},${it.high},${it.low},${it.firstLeft},${it.lowLeft}" })
        appendTable(out, "4) HIGH -> LOW -> RECOVERY: ALL THREE-LEVEL PATHS",
            "side,first_high,drop_to,recovered_to", recoveries, holdout,
            { "${side(it.side)},${it.high},${it.low},${it.recovery}" },
            sides.flatMap { s -> highs.flatMap { h ->
                (10 until h step 10).flatMap { low ->
                    ((low + 10)..h step 10).map { up -> "${side(s)},$h,$low,$up" }
                }
            } })
        appendTable(out, "5) HIGH -> LOW -> RECOVERY BY RECOVERY MINUTE (15 TO 0)",
            "side,first_high,drop_to,recovered_to,recovery_minute_left",
            recoveries, holdout,
            { "${side(it.side)},${it.high},${it.low},${it.recovery},${it.left}" },
            sides.flatMap { s -> highs.flatMap { h ->
                (10 until h step 10).flatMap { low ->
                    ((low + 10)..h step 10).flatMap { up ->
                        (15 downTo 0).map { minute -> "${side(s)},$h,$low,$up,$minute" }
                    }
                }
            } })
        appendTable(out, "6) HIGH -> LOW -> RECOVERY BY ALL THREE EXACT MINUTES (OBSERVED ROWS)",
            "side,first_high,drop_to,recovered_to,first_high_minute_left,drop_minute_left,recovery_minute_left",
            recoveries, holdout,
            { "${side(it.side)},${it.high},${it.low},${it.recovery},${it.firstLeft},${it.lowLeft},${it.left}" })
        appendTable(out, "7) FIRST REACH OF 60/70/80/90 BY EACH MINUTE",
            "side,first_high,minute_left", firstHits, holdout,
            { "${side(it.side)},${it.high},${it.left}" },
            sides.flatMap { s -> highs.flatMap { h ->
                (15 downTo 0).map { m -> "${side(s)},$h,$m" }
            } })
        appendTable(out, "8) ACTUAL CURRENT QUOTE BIN AT EACH MINUTE (NOT NECESSARILY FIRST HIT)",
            "side,observed_midpoint_10pct_bin,minute_left", snapshots, holdout,
            { "${side(it.side)},${it.low},${it.left}" },
            sides.flatMap { s -> (0..100 step 10).flatMap { bin ->
                (15 downTo 0).map { minute -> "${side(s)},$bin,$minute" }
            } })
        out.appendLine("INTERPRETATION: e.g. YES,80,60,10 describes a market that first")
        out.appendLine("reached at least 80 earlier, subsequently had a 1-minute YES")
        out.appendLine("midpoint at or below 60 with 10 minutes left. Win% is how")
        out.appendLine("often the YES contract actually settled YES after that observation.")
        out.appendLine("If the price skipped 60, the ask is the observed lower price, not 60 cents.")
        out.appendLine("The historical price drop does not preserve the earlier 80% probability.")
        return out.toString()
    }
}
