package com.decisionoverlay.checker

import java.time.Instant
import java.util.Locale

/**
 * A phone-friendly summary of the full observed BTC 15-minute market paths.
 * All observed events are counted, but empty exact-minute tables are NOT printed.
 */
object KalshiCompactReport {
    private val highs = listOf(60, 70, 80, 90)
    private val sides = listOf(1, -1)
    private val minuteLabels = listOf("15-11", "10-6", "5-4", "3-1")

    private data class Key(val side: Int, val high: Int, val low: Int = 0,
                           val recovery: Int = 0) {
        fun label(): String {
            val prefix = if (side == 1) "YES" else "NO"
            return when {
                recovery != 0 -> "$prefix $high->$low->$recovery"
                low != 0 -> "$prefix $high->$low"
                else -> "$prefix $high"
            }
        }
    }

    private data class Point(val left: Int, val probability: Double, val ask: Double)

    private class Stats {
        var n = 0
        var wins = 0
        var ask = 0.0
        var fee = 0.0
        var net = 0.0
        var firstMinutes = 0.0
        var dropMinutes = 0.0
        var entryMinutes = 0.0
        var oldN = 0
        var oldNet = 0.0
        var recentN = 0
        var recentWins = 0
        var recentNet = 0.0
        fun add(won: Boolean, entryAsk: Double, first: Int, drop: Int, entry: Int,
                recent: Boolean) {
            n++
            if (won) wins++
            val charge = KalshiPatternMath.approximateFee(entryAsk)
            val profit = (if (won) 1.0 else 0.0) - entryAsk - charge
            ask += entryAsk
            fee += charge
            net += profit
            firstMinutes += first
            dropMinutes += drop
            entryMinutes += entry
            if (recent) {
                recentN++
                if (won) recentWins++
                recentNet += profit
            } else {
                oldN++
                oldNet += profit
            }
        }
    }

    private class ReachStats {
        var n = 0
        var wins = 0
        var dipped = 0
        var stayed = 0
        var unknown = 0
        var firstLeft = 0.0
        fun add(r: KalshiPatternMath.Reach) {
            n++
            if (r.won) wins++
            firstLeft += r.left
            when (r.fellBelow) {
                true -> dipped++
                false -> stayed++
                null -> unknown++
            }
        }
    }

    private fun format(value: Double) = String.format(Locale.US, "%.1f", value)
    private fun percent(wins: Int, n: Int): String =
        if (n == 0) "n/a" else format(100.0 * wins / n) + "%"
    private fun cents(sum: Double, n: Int, signed: Boolean = false): String =
        if (n == 0) "n/a" else String.format(Locale.US,
            if (signed) "%+.1f" else "%.1f", 100.0 * sum / n)

    private fun details(key: Key, s: Stats?, timing: Boolean = true): String {
        if (s == null || s.n == 0) return "${key.label()}: n=0 (no observations)"
        val recent = "${s.recentWins}/${s.recentN} (${percent(s.recentWins, s.recentN)})"
        val whenSeen = if (!timing) "" else when {
            key.recovery != 0 -> " | avg minutes left high/drop/recovery: " +
                "${format(s.firstMinutes/s.n)}/${format(s.dropMinutes/s.n)}/${format(s.entryMinutes/s.n)}"
            key.low != 0 -> " | avg minutes left high/drop: " +
                "${format(s.firstMinutes/s.n)}/${format(s.entryMinutes/s.n)}"
            else -> " | avg minutes left: ${format(s.entryMinutes/s.n)}"
        }
        return "${key.label()}: ${s.wins}/${s.n} (${percent(s.wins, s.n)})" +
            " | ask ${cents(s.ask, s.n)}c fee ${cents(s.fee, s.n)}c" +
            " net ${cents(s.net, s.n, signed = true)}c" +
            " | newest 30% $recent" + whenSeen +
            if (s.n < 30) " | SMALL SAMPLE" else ""
    }

    private fun band(minuteLeft: Int): Int = when (minuteLeft) {
        in 11..15 -> 0
        in 6..10 -> 1
        in 4..5 -> 2
        else -> 3
    }

    fun report(markets: List<KalshiPatternMath.Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No valid settled markets to analyze; skipped: $skipped."
        val sorted = markets.sortedBy { it.closeTs }
        val recent = sorted.drop(sorted.size * 7 / 10).map { it.ticker }.toSet()
        val reached = linkedMapOf<Key, ReachStats>()
        val pullbacks = linkedMapOf<Key, Stats>()
        val recoveries = linkedMapOf<Key, Stats>()
        val focus = listOf(
            Key(1, 80, 50, 60), Key(1, 80, 60, 70),
            Key(-1, 80, 50, 60), Key(-1, 80, 60, 70))
        val windowStats = focus.associateWith { Array(4) { Stats() } }
        // These aggregates span ALL recovered paths, not just the most common ones.
        // One market may contribute several overlapping path rules.
        val allRecoveryWindows = sides.associateWith { Array(4) { Stats() } }
        val recoveryShapes = linkedMapOf<Triple<Int, String, String>, Stats>()
        var validQuotes = 0

        for (market in sorted) {
            val isRecent = market.ticker in recent
            val quotes = market.quotes.filter { it.valid() }
                .sortedByDescending { it.left }.distinctBy { it.left }
            validQuotes += quotes.size
            for (hit in KalshiPatternMath.analyze(market).reaches) {
                reached.getOrPut(Key(hit.side, hit.level)) { ReachStats() }.add(hit)
            }
            for (side in sides) {
                val won = if (side == 1) market.yesWon else !market.yesWon
                val points = quotes.map {
                    Point(it.left, it.strength(side), it.entryAsk(side))
                }
                for (high in highs) {
                    val firstIndex = points.indexOfFirst { it.probability + 1e-8 >= high }
                    if (firstIndex < 0) continue
                    val first = points[firstIndex]
                    for (low in 10 until high step 10) {
                        val lowIndex = ((firstIndex + 1) until points.size)
                            .firstOrNull { points[it].probability <= low + 1e-8 }
                            ?: continue
                        val down = points[lowIndex]
                        val pullKey = Key(side, high, low)
                        pullbacks.getOrPut(pullKey) { Stats() }
                            .add(won, down.ask, first.left, down.left, down.left, isRecent)
                        for (recovery in (low + 10)..high step 10) {
                            val recoveryIndex = ((lowIndex + 1) until points.size)
                                .firstOrNull { points[it].probability + 1e-8 >= recovery }
                                ?: continue
                            val up = points[recoveryIndex]
                            val recoveryKey = Key(side, high, low, recovery)
                            recoveries.getOrPut(recoveryKey) { Stats() }
                                .add(won, up.ask, first.left, down.left, up.left, isRecent)
                            windowStats[recoveryKey]?.get(band(up.left))?.add(
                                won, up.ask, first.left, down.left, up.left, isRecent)
                            allRecoveryWindows.getValue(side)[band(up.left)].add(
                                won, up.ask, first.left, down.left, up.left, isRecent)
                            val dropSize = when (high - low) {
                                10 -> "10"
                                20 -> "20"
                                else -> "30+"
                            }
                            val reboundSize = if (recovery - low == 10) "10" else "20+"
                            recoveryShapes.getOrPut(Triple(side, dropSize, reboundSize)) { Stats() }
                                .add(won, up.ask, first.left, down.left, up.left, isRecent)
                        }
                    }
                }
            }
        }

        val out = StringBuilder(9000)
        out.appendLine("=== COMPACT BTC 15-MINUTE KALSHI BACKTEST ===")
        out.appendLine("Settled markets: ${sorted.size}; skipped/empty: $skipped; usable 1-minute quotes: $validQuotes")
        out.appendLine("UTC close range: ${Instant.ofEpochSecond(sorted.first().closeTs)} to ${Instant.ofEpochSecond(sorted.last().closeTs)}")
        out.appendLine("Session-start filter: 10 AM-11 PM Eastern (applied in scanner); YES and NO evaluated separately.")
        out.appendLine("Win% = actual side settlement wins / observed matching markets, NOT quoted probability.")
        out.appendLine("Ask/fee/net = average cents per hypothetical 1-contract entry at the observed ask.")
        out.appendLine("Newest 30% is a chronological holdout, not a guarantee; overlapping patterns reuse markets.")
        out.appendLine("Timing = average whole minutes LEFT at each event; 15-13 and 3-1 are research-only.")
        out.appendLine()

        out.appendLine("1. FIRST REACH: wins, observed later drops/stays/unknown")
        for (side in sides) for (high in highs) {
            val key = Key(side, high)
            val s = reached[key]
            if (s == null) {
                out.appendLine("${key.label()}: 0 observed")
            } else {
                out.appendLine("${key.label()}: wins ${s.wins}/${s.n} (${percent(s.wins, s.n)}), " +
                    "dipped ${s.dipped}, stayed ${s.stayed}, unknown ${s.unknown}, " +
                    "avg first-minute-left ${format(s.firstLeft/s.n)}")
            }
        }
        out.appendLine()

        out.appendLine("2. MOST COMMON HIGH -> LOW PULLBACKS (top 10 by observed n)")
        pullbacks.entries.sortedWith(compareByDescending<Map.Entry<Key, Stats>> { it.value.n }
            .thenBy { it.key.label() }).take(10).forEach {
            out.appendLine(details(it.key, it.value))
        }
        out.appendLine()

        out.appendLine("3. MOST COMMON HIGH -> LOW -> RECOVERY (top 12 by observed n)")
        recoveries.entries.sortedWith(compareByDescending<Map.Entry<Key, Stats>> { it.value.n }
            .thenBy { it.key.label() }).take(12).forEach {
            out.appendLine(details(it.key, it.value))
        }
        out.appendLine()

        out.appendLine("4. REQUESTED RECOVERY PATHS (always shown even if n=0)")
        for (key in focus) out.appendLine(details(key, recoveries[key]))
        out.appendLine()

        out.appendLine("5. RECOVERY TIMING: same focus patterns split by minute-left ranges")
        out.appendLine("The range is when the recovery is FIRST observed, not when the trade fills.")
        for (key in focus) {
            val windows = windowStats.getValue(key)
            out.appendLine(key.label() + ": " + windows.indices.joinToString(" | ") { i ->
                val s = windows[i]
                "${minuteLabels[i]}m n=${s.n} win=${percent(s.wins, s.n)}"
            })
        }
        out.appendLine()

        out.appendLine("6. ALL RECOVERIES BY DROP/REBOUND SIZE (overlapping pattern-events)")
        for (side in sides) for (drop in listOf("10", "20", "30+"))
            for (rebound in listOf("10", "20+")) {
                val key = Triple(side, drop, rebound)
                val stat = recoveryShapes[key]
                val name = "${if (side == 1) "YES" else "NO"} drop $drop, rebound $rebound"
                if (stat == null || stat.n == 0) {
                    out.appendLine("$name: n=0")
                } else {
                    out.appendLine("$name: ${stat.wins}/${stat.n} (${percent(stat.wins,stat.n)}), " +
                        "ask ${cents(stat.ask,stat.n)}c, net ${cents(stat.net,stat.n,true)}c")
                }
            }
        out.appendLine("These are counts of matched RULES, not unique markets; do not add to infer independent trials.")
        out.appendLine()

        out.appendLine("7. ALL RECOVERY EVENTS BY TIME LEFT (all path rules, overlapping)")
        for (side in sides) {
            val windows = allRecoveryWindows.getValue(side)
            out.appendLine((if (side == 1) "YES" else "NO") + ": " +
                windows.indices.joinToString(" | ") { i ->
                    val stat = windows[i]
                    "${minuteLabels[i]}m n=${stat.n} win=${percent(stat.wins,stat.n)}"
                })
        }
        out.appendLine()

        out.appendLine("8. RESEARCH CANDIDATES: ranked by OLDER 70% mean net; newest 30% shown separately")
        val candidates = recoveries.entries.filter {
            it.value.oldN >= 30 && it.value.recentN >= 15 &&
                it.value.oldNet > 0 && it.value.entryMinutes / it.value.n in 4.0..12.0
        }.sortedByDescending { it.value.oldNet / it.value.oldN }.take(5)
        if (candidates.isEmpty()) out.appendLine("No recovery path passed the sample/timing filters.")
        for ((key, s) in candidates) {
            out.appendLine("${key.label()}: older n=${s.oldN}, avg net ${cents(s.oldNet,s.oldN,true)}c; " +
                "newest ${s.recentWins}/${s.recentN} (${percent(s.recentWins,s.recentN)}), " +
                "avg net ${cents(s.recentNet,s.recentN,true)}c")
        }
        out.appendLine()
        out.appendLine("NOTE: All levels use observed 1-minute quote midpoints. Gaps can hide crossings.")
        out.appendLine("Threshold names are NOT the entry price. Fees are approximate; no liquidity/slippage model.")
        out.appendLine("No trade recommendation. Strong-looking small samples and multiple comparisons can mislead.")
        return out.toString()
    }
}
