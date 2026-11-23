package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs

internal object BtcQuarterReversals {
    private data class Key(val start: Int?, val end: Int, val left: Int, val filtered: Boolean)
    private data class Count(var hits: Int = 0, var wins: Int = 0)

    private fun clear(bar: BinanceTwentySevenPatternReport.Observation, target: Double, above: Boolean) =
        if (above) bar.low > target else bar.high < target

    private fun percent(n: Int, d: Int) =
        if (d == 0) "NA" else String.format(Locale.US, "%.2f", 100.0 * n / d)

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>
    ): String {
        val counts = mutableMapOf<Key, Count>()
        for (market in markets) {
            val minutes = market.minutes.associateBy { it.minute }
            for ((minute, bar) in minutes) {
                val end = BtcExhaustivePatternReport.band(bar.signedChartLevel) ?: continue
                val above = end > 0
                if (!clear(bar, market.targetBtc, above)) continue
                val starts = mutableListOf<Int?>()
                if (abs(end) >= 60) starts.add(null)
                val prior = minutes[minute - 1]
                if (prior != null && clear(prior, market.targetBtc, above)) {
                    val start = BtcExhaustivePatternReport.band(prior.signedChartLevel)
                    if (start != null && (start > 0) == above && abs(start) > abs(end)) starts.add(start)
                }
                val qualified = BtcEarlyIndicatorResearchReport.Gate.VOL_MOM.accepts(
                    features[market.startMs]?.get(minute), !above)
                for (start in starts) for (filtered in listOf(false, true)) {
                    if (filtered && !qualified) continue
                    val count = counts.getOrPut(Key(start, end, 15 - minute, filtered)) { Count() }
                    count.hits++
                    if (market.upWon != above) count.wins++
                }
            }
        }
        return buildString {
            appendLine("BTC REVERSALS BY EXACT MINUTE · ${markets.size} completed markets")
            appendLine("Signed chart bands are proxy levels. Reference cost assumes 100 minus the entry band in cents. No Kalshi quotes, fees or fills.")
            appendLine("Entry at completed minute close. Every candle in the setup must stay strictly on its starting side, including wicks. Win = final BTC close on opposite side of midpoint target; tied markets excluded.")
            appendLine("Hit % = distinct matching markets / all tested markets. Win % = wins / hits. Rows overlap across rules and times. Filter = 3m momentum toward reversal AND relative volume >= 1.2x.")
            appendLine("setup,start_band,entry_band,buy,minutes_left,filter,hits,hit_pct,wins,win_pct,reference_cents,meets_2x_before_fees")
            for (sign in listOf(1, -1)) for (end in 90 downTo 50 step 10) {
                val starts = mutableListOf<Int?>()
                if (end >= 60) starts.add(null)
                starts.addAll((90 downTo end + 10 step 10).map { sign * it })
                for (start in starts) for (left in 14 downTo 1) for (filtered in listOf(false, true)) {
                    if (start != null && left == 14) continue
                    val c = counts[Key(start, sign * end, left, filtered)] ?: Count()
                    val cost = 100 - end
                    val meets = if (c.hits == 0) "NA" else (100 * c.wins >= 2 * cost * c.hits).toString()
                    appendLine(listOf(if (start == null) "level" else "1m drop", start ?: "", sign * end,
                        if (sign > 0) "DOWN" else "UP", left, if (filtered) "momentum+volume" else "none",
                        c.hits, percent(c.hits, markets.size), c.wins, percent(c.wins, c.hits), cost, meets).joinToString(","))
                }
            }
        }
    }
}
