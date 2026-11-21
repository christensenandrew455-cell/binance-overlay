package com.decisionoverlay.checker

import java.util.Locale

/**
 * Research-only reversal test on the custom signed BTC chart INDEX.
 * No values here are Kalshi contract quotes, calibrated probabilities, or trade P&L.
 * All decisions use completed one-minute chart bars.
 */
internal object BtcContrarianReversalResearchReport {
    internal data class Setup(
        val from: Int?, val to: Int, val gap: Int, val buyUp: Boolean
    ) {
        val label: String get() = if (from == null) {
            "at ${signed(to)}"
        } else "${signed(from)} to ${signed(to)} in ${gap}m"
        val side: String get() = if (buyUp) "UP" else "DOWN"
        val kind: String get() = when {
            from == null -> "EXTREME SNAPSHOT"
            from * to < 0 -> "CROSS-TARGET"
            else -> "10-POINT PULLBACK"
        }
    }

    private fun signed(n: Int) = (if (n > 0) "+" else "") + n
    private fun band(n: Double?) = BtcExhaustivePatternReport.band(n ?: Double.NaN)

    // Fixed before reading results: 10-point moves toward the target, both directions,
    // extreme index snapshots and the explicitly requested cross-target case.
    internal val setups = buildList {
        for (sign in listOf(-1, 1)) {
            for (at in listOf(80, 90)) {
                add(Setup(null, sign * at, 0, sign < 0))
            }
            for (from in listOf(60, 70, 80, 90)) {
                for (gap in 1..2) {
                    add(Setup(sign * from, sign * (from - 10), gap, sign < 0))
                }
            }
        }
        for (gap in 1..2) {
            add(Setup(-60, +50, gap, false))
            add(Setup(+60, -50, gap, true))
        }
    }

    internal data class Result(val marketIndex: Int, val minutesLeft: Int, val won: Boolean)
    internal fun outcomes(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>,
        setup: Setup,
        gate: BtcEarlyIndicatorResearchReport.Gate
    ): List<Result> = markets.mapIndexedNotNull { index, market ->
        val byMinute = market.minutes.associateBy { it.minute }
        val first = (if (setup.gap == 0) 1 else setup.gap + 1)
        val minute = (first..14).firstOrNull { t ->
            band(byMinute[t]?.signedChartLevel) == setup.to &&
                (setup.from == null ||
                    band(byMinute[t - setup.gap]?.signedChartLevel) == setup.from) &&
                gate.accepts(features[market.startMs]?.get(t), setup.buyUp)
        } ?: return@mapIndexedNotNull null
        Result(index, 15 - minute, market.upWon == setup.buyUp)
    }

    internal data class Stats(val hits: Int, val wins: Int, val meanLeft: Double) {
        val winPct get() = if (hits == 0) Double.NaN else 100.0 * wins / hits
        // A break-even ask CEILING, before fees, inferred from outcomes alone.
        // NOT an observed Kalshi ask, nor an executable trade opportunity.
        val theoreticalBreakEvenCents get() = winPct
    }
    private fun stats(rows: List<Result>) = Stats(
        rows.size, rows.count { it.won },
        if (rows.isEmpty()) Double.NaN else rows.map { it.minutesLeft }.average()
    )
    private fun fmt(n: Double) =
        if (n.isFinite()) String.format(Locale.US, "%.1f", n) else "N/A"

    fun report(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>
    ): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.size < 2) return "Too few settled BTC markets for reversal research."
        val oldSize = (ordered.size * 7 / 10).coerceIn(1, ordered.lastIndex)
        val newSize = ordered.size - oldSize
        val gates = listOf(
            BtcEarlyIndicatorResearchReport.Gate.NONE,
            BtcEarlyIndicatorResearchReport.Gate.MOMENTUM,
            BtcEarlyIndicatorResearchReport.Gate.VOLUME,
            BtcEarlyIndicatorResearchReport.Gate.COUNTER
        )
        return buildString {
            appendLine("=== 10-POINT REVERSALS AND LONG-SHOT COUNTER-SIDE TEST (RESEARCH ONLY) ===")
            appendLine("Completed markets: ${ordered.size}/1000 | oldest ${oldSize} discovery, newest ${newSize} comparison.")
            if (ordered.size != 1000) appendLine("INCOMPLETE: results provisional.")
            appendLine("All levels here are SIGNED CUSTOM BTC CHART INDEX BANDS, not Kalshi YES/NO prices.")
            appendLine("Example: at +90 BUY DOWN means chart index +90, NOT a 90-cent YES ask.")
            appendLine("Specifically includes +60 to +50, -60 to -50, both +/-90 extremes,")
            appendLine("and -60 to +50 / +60 to -50 target crossings (110 index points, NOT 10).")
            appendLine("Counter-side wins = BTC settles opposite the chart's current signed side.")
            appendLine("Only completed candles determine the setup and volume/momentum gate.")
            appendLine("Test includes unfiltered setups; optional ONE-indicator gates are shown")
            appendLine("separately so requiring all indicators cannot silently erase coverage.")
            appendLine("Each setup/gate counts at most one earliest occurrence per market.")
            appendLine("Rows and setups OVERLAP: hit counts must NOT be summed as independent trades.")
            appendLine("Historical win% is only a theoretical break-even BUY ask ceiling in cents")
            appendLine("BEFORE spread and fees, IF that entry quote could actually have been obtained.")
            appendLine("NO historical Kalshi order-book asks, executable fills, fees, or realized profits")
            appendLine("in this 1,000-market Binance-derived dataset. NO LIVE SIGNALS ALTERED.")
            appendLine()
            appendLine("=== ALL SETUPS AND SINGLE-INDICATOR COMPARISONS (CSV) ===")
            appendLine("type,setup,buy_side,filter,older_hits,older_hit_pct,older_wins," +
                "older_win_pct,newer_hits,newer_hit_pct,newer_wins,newer_win_pct," +
                "all_hits,all_hit_pct,filter_retention_pct,newer_mean_min_left," +
                "newer_break_even_ask_cents_BEFORE_FEES_NOT_MARKET_PRICE")
            for (setup in setups) {
                val unfiltered = outcomes(ordered, features, setup,
                    BtcEarlyIndicatorResearchReport.Gate.NONE)
                for (gate in gates) {
                    val rows = if (gate == BtcEarlyIndicatorResearchReport.Gate.NONE)
                        unfiltered else outcomes(ordered, features, setup, gate)
                    val older = stats(rows.filter { it.marketIndex < oldSize })
                    val newer = stats(rows.filter { it.marketIndex >= oldSize })
                    appendLine(
                        "${setup.kind},${setup.label},${setup.side},${gate.label}," +
                        "${older.hits},${fmt(100.0 * older.hits / oldSize)},${older.wins}," +
                        "${fmt(older.winPct)},${newer.hits},${fmt(100.0 * newer.hits / newSize)}," +
                        "${newer.wins},${fmt(newer.winPct)},${rows.size}," +
                        "${fmt(100.0 * rows.size / ordered.size)}," +
                        "${fmt(if (unfiltered.isEmpty()) Double.NaN else 100.0 * rows.size / unfiltered.size)}," +
                        "${fmt(newer.meanLeft)},${fmt(newer.theoreticalBreakEvenCents)}"
                    )
                }
            }
            appendLine()
            appendLine("=== UNFILTERED 10-POINT / EXTREME SETUPS WITH >=20 OLDER MATCHES ===")
            val summaries = setups.map { setup ->
                val rows = outcomes(ordered, features, setup, BtcEarlyIndicatorResearchReport.Gate.NONE)
                Triple(setup, stats(rows.filter { it.marketIndex < oldSize }),
                    stats(rows.filter { it.marketIndex >= oldSize }))
            }.filter { it.second.hits >= 20 }.sortedByDescending { it.second.hits }
            if (summaries.isEmpty()) appendLine("No setups have 20 older matches.")
            for ((setup, older, newer) in summaries) {
                appendLine("${setup.label} BUY ${setup.side} | older n=${older.hits} win=${fmt(older.winPct)}%" +
                    " | newer n=${newer.hits} win=${fmt(newer.winPct)}%" +
                    " | avg newest min left=${fmt(newer.meanLeft)}")
            }
            appendLine("An actual low-priced reversal trade needs time-matched Kalshi NO/YES asks.")
            appendLine("Assess profit using win probability - executable ask - actual fees,")
            appendLine("and compare net profit per 1,000 markets, not win rate alone.")
            appendLine("=== END CONTRARIAN RESEARCH; MAIN GRAPH AND ALERTS UNCHANGED ===")
        }
    }
}
