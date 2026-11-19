package com.decisionoverlay.checker

import java.util.Locale

/** Seven supplied path shapes translated to a Binance chart centered on target 50. */
object BinanceSevenPathReport {
    data class Path(val id: Int, val rule: BinanceTwentySevenPatternReport.Rule)

    // Target=50; above target uses 60/70/80/90, below target uses 40/30/20/10.
    // Only the path shapes transfer from the image; its numeric labels do not.
    val paths = listOf(
        Path(1, BinanceTwentySevenPatternReport.Rule(listOf(40, 60), false)),
        Path(2, BinanceTwentySevenPatternReport.Rule(listOf(50, 60), false)),
        Path(3, BinanceTwentySevenPatternReport.Rule(listOf(40, 20), true)),
        Path(4, BinanceTwentySevenPatternReport.Rule(listOf(50, 20), true)),
        Path(5, BinanceTwentySevenPatternReport.Rule(listOf(30, 20), true)),
        Path(6, BinanceTwentySevenPatternReport.Rule(listOf(60, 20), true)),
        Path(7, BinanceTwentySevenPatternReport.Rule(listOf(70, 60), false))
    )

    data class Result(val path: Path, val hits: Int, val wins: Int, val markets: Int) {
        val hitRatePct: Double get() = if (markets == 0) 0.0 else 100.0 * hits / markets
        val winRatePct: Double get() = if (hits == 0) Double.NaN else 100.0 * wins / hits
    }

    fun results(markets: List<BinanceTwentySevenPatternReport.Market>): List<Result> {
        val unique = markets.distinctBy { it.startMs }
        return paths.map { path ->
            val hits = unique.mapNotNull {
                BinanceTwentySevenPatternReport.firstHit(it, path.rule)
            }
            Result(path, hits.size, hits.count { it.won }, unique.size)
        }
    }

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        fun n(x: Double) = if (x.isFinite()) String.format(Locale.US, "%.1f", x) else "n/a"
        val out = StringBuilder()
        out.appendLine("=== SEVEN CHART PATHS — TARGET-CENTERED 50 SCALE ===")
        out.appendLine("50=BTC target; above: 60/70/80/90; below: 40/30/20/10.")
        out.appendLine("Path,buy,hits,hit_rate_pct,wins,win_rate_pct")
        for (r in results(markets)) {
            out.appendLine(listOf(
                "%02d %s".format(Locale.US, r.path.id, r.path.rule.label),
                r.path.rule.direction, r.hits, n(r.hitRatePct), r.wins,
                n(r.winRatePct)
            ).joinToString(","))
        }
        return out.toString()
    }
}
