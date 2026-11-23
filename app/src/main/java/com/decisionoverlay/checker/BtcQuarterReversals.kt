package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs

internal object BtcQuarterReversals {
    private data class Key(val start: Int?, val end: Int, val left: Int, val filtered: Boolean)
    private data class Count(var hits: Int = 0, var wins: Int = 0)

    private enum class Call { BASELINE, DOWN, UP }
    private data class DirectionKey(val band: Int, val left: Int, val call: Call)

    private fun clear(bar: BinanceTwentySevenPatternReport.Observation, target: Double, above: Boolean) =
        if (above) bar.low > target else bar.high < target

    private fun percent(n: Int, d: Int) =
        if (d == 0) "NA" else String.format(Locale.US, "%.2f", 100.0 * n / d)

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>
    ): String {
        val counts = mutableMapOf<Key, Count>()
        val directionCounts = mutableMapOf<DirectionKey, Count>()
        for (market in markets) {
            val minutes = market.minutes.associateBy { it.minute }
            for ((minute, bar) in minutes) {
                val end = BtcExhaustivePatternReport.band(bar.signedChartLevel) ?: continue
                val above = end > 0
                if (!clear(bar, market.targetBtc, above)) continue
                val indicators = features[market.startMs]?.get(minute)
                if (abs(end) in listOf(80, 90) && minute in 1..14) {
                    val gate = BtcEarlyIndicatorResearchReport.Gate.VOL_MOM
                    for (call in Call.values()) {
                        if (call != Call.BASELINE && !gate.accepts(indicators, call == Call.UP)) continue
                        val key = DirectionKey(end, 15 - minute, call)
                        val count = directionCounts.getOrPut(key) { Count() }
                        count.hits++
                        if (!market.upWon) count.wins++ // DOWN finishes, for every cohort.
                    }
                }
                val starts = mutableListOf<Int?>()
                if (abs(end) >= 60) starts.add(null)
                val prior = minutes[minute - 1]
                if (prior != null && clear(prior, market.targetBtc, above)) {
                    val start = BtcExhaustivePatternReport.band(prior.signedChartLevel)
                    if (start != null && (start > 0) == above && abs(start) > abs(end)) starts.add(start)
                }
                val qualified = BtcEarlyIndicatorResearchReport.Gate.VOL_MOM.accepts(
                    indicators, !above)
                for (start in starts) for (filtered in listOf(false, true)) {
                    if (filtered && !qualified) continue
                    val count = counts.getOrPut(Key(start, end, 15 - minute, filtered)) { Count() }
                    count.hits++
                    if (market.upWon != above) count.wins++
                }
            }
        }
        return buildString {
            appendLine("BTC DIRECTION AT 80 AND 90 · ${markets.size} completed markets")
            appendLine("Each completed minute: predict the sign of 3m momentum when relative volume >= 1.2x. Otherwise no call. BASELINE includes all eligible observations at the same band and minute.")
            appendLine("Hit % = signals / all markets. DOWN % = markets finishing below target / signals. Call win % = correct UP or DOWN calls / signals. Outcomes use the final close.")
            appendLine("signed_band,minutes_left,call,signals,hit_pct,down_finishes,down_pct,up_finishes,up_pct,call_wins,call_win_pct")
            for (band in listOf(90, 80, -90, -80)) for (left in 14 downTo 1) for (call in Call.values()) {
                val c = directionCounts[DirectionKey(band, left, call)] ?: Count()
                val down = c.wins
                val up = c.hits - down
                val wins = when (call) {
                    Call.UP -> up
                    Call.DOWN -> down
                    Call.BASELINE -> null
                }
                appendLine(listOf(band, left, call, c.hits, percent(c.hits, markets.size),
                    down, percent(down, c.hits), up, percent(up, c.hits),
                    wins ?: "NA", wins?.let { percent(it, c.hits) } ?: "NA").joinToString(","))
            }
            appendLine()
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
