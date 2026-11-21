package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.sqrt

/**
 * Research-only study of first *completed-minute* extreme-index touches.
 * Earlier opposite-side touches can occur ANY minute before the extreme;
 * no fixed 1m or 2m adjacency is imposed.
 *
 * The chart's 80/90 are custom BTC distance INDEX bands, not Kalshi odds.
 */
internal object BtcAnytimeExtremeResearchReport {
    internal enum class Prior(val label: String, val threshold: Int) {
        ANY("none", 0),
        OPPOSITE_50("previously touched opposite side (50+)", 50),
        OPPOSITE_80("previously touched opposite 80+", 80),
        OPPOSITE_90("previously touched opposite 90", 90)
    }
    internal data class Query(val extreme: Int, val prior: Prior) {
        val side get() = if (extreme > 0) "DOWN" else "UP"
        val label get() = (if (extreme > 0) "+" else "") + extreme
    }

    internal val queries: List<Query> = buildList {
        for (extreme in listOf(90, -90, 80, -80))
            for (prior in Prior.entries) add(Query(extreme, prior))
    }

    internal data class Hit(
        val marketIndex: Int,
        val minute: Int,
        val wonOpposite: Boolean,
        val returnedOppositeBeforeClose: Boolean
    ) {
        val left get() = 15 - minute
    }
    private fun level(value: Double) = BtcExhaustivePatternReport.band(value)
    private fun fmt(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "N/A"

    // The prior extreme or cross can happen at ANY earlier closed bar.
    internal fun hasPrior(
        byMinute: Map<Int, Int>, beforeMinute: Int, extreme: Int, prior: Prior
    ): Boolean {
        if (prior == Prior.ANY) return true
        val side = if (extreme > 0) 1 else -1
        return (1 until beforeMinute).any { earlier ->
            val earlierBand = byMinute[earlier] ?: return@any false
            side * earlierBand <= -prior.threshold
        }
    }

    internal fun hits(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>,
        query: Query, gate: BtcEarlyIndicatorResearchReport.Gate
    ): List<Hit> = markets.mapIndexedNotNull { index, market ->
        val byMinute = market.minutes.asSequence()
            .filter { it.minute in 1..14 }
            .sortedBy { it.minute }
            .distinctBy { it.minute }
            .mapNotNull { point ->
                val b = level(point.signedChartLevel) ?: return@mapNotNull null
                point.minute to b
            }.toMap()

        // Counter-side position: BUY DOWN at +90 and BUY UP at -90.
        val buyUp = query.extreme < 0
        val t = (1..14).firstOrNull { minute ->
            byMinute[minute] == query.extreme &&
                hasPrior(byMinute, minute, query.extreme, query.prior) &&
                gate.accepts(features[market.startMs]?.get(minute), buyUp)
        } ?: return@mapIndexedNotNull null
        val returned = (t + 1..14).any { later ->
            val b = byMinute[later] ?: return@any false
            if (buyUp) b >= 50 else b <= -50
        }
        Hit(index, t, market.upWon == buyUp, returned)
    }


    /**
     * Exact question: hit a threshold on one side at ANY minute, THEN close
     * a subsequent minute in the other signed side, AND settle on that side.
     * 80 is an 80+ threshold (includes 90); 90 means the 90 band.
     * One earliest threshold hit per direction per market.
     */
    internal data class FlipEvent(
        val marketIndex: Int,
        val firstMinute: Int,
        val settledOpposite: Boolean,
        val crossedAfterTouch: Boolean
    ) {
        val fullyFlippedAndSettled get() = crossedAfterTouch && settledOpposite
        val leftAtTrigger get() = 15 - firstMinute
    }

    internal fun flipEvents(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        threshold: Int,
        touchedUp: Boolean
    ): List<FlipEvent> {
        require(threshold == 80 || threshold == 90)
        return markets.mapIndexedNotNull { index, market ->
            val bands = market.minutes.asSequence()
                .filter { it.minute in 1..14 }
                .sortedBy { it.minute }
                .distinctBy { it.minute }
                .mapNotNull { obs ->
                    val b = level(obs.signedChartLevel) ?: return@mapNotNull null
                    obs.minute to b
                }.toMap()
            val touch = (1..14).firstOrNull { minute ->
                val b = bands[minute] ?: return@firstOrNull false
                if (touchedUp) b >= threshold else b <= -threshold
            } ?: return@mapIndexedNotNull null
            val oppositeAfter = (touch + 1..14).any { minute ->
                val b = bands[minute] ?: return@any false
                if (touchedUp) b <= -50 else b >= 50
            }
            FlipEvent(index, touch, market.upWon != touchedUp, oppositeAfter)
        }
    }

    private fun StringBuilder.flipSummaryLine(
        threshold: Int, label: String, events: List<FlipEvent>, total: Int
    ) {
        val touching = events.size
        val endedOpposite = events.count { it.settledOpposite }
        val fullyFlipped = events.count { it.fullyFlippedAndSettled }
        val crossed = events.count { it.crossedAfterTouch }
        val avgLeft = if (events.isEmpty()) Double.NaN else events.map { it.leftAtTrigger }.average()
        appendLine("$threshold,$label,$total,$touching," +
            "${fmt(100.0 * touching / total)},$endedOpposite," +
            "${fmt(if (touching == 0) Double.NaN else 100.0 * endedOpposite / touching)}," +
            "$crossed,$fullyFlipped," +
            "${fmt(if (touching == 0) Double.NaN else 100.0 * fullyFlipped / touching)}," +
            "${fmt(100.0 * fullyFlipped / total)},${fmt(avgLeft)}")
    }

    internal data class Stats(
        val count: Int, val wins: Int, val returned: Int, val meanLeft: Double
    ) {
        val winPct get() = if (count == 0) Double.NaN else 100.0 * wins / count
        val returnPct get() = if (count == 0) Double.NaN else 100.0 * returned / count
        val wilsonLow: Double get() {
            if (count == 0) return Double.NaN
            val p = wins.toDouble() / count
            val z = 1.96
            val denom = 1.0 + z * z / count
            return 100.0 * (p + z * z / (2 * count) -
                z * sqrt(p * (1 - p) / count + z * z / (4.0 * count * count))) / denom
        }
    }
    private fun stats(rows: List<Hit>) = Stats(
        rows.size, rows.count { it.wonOpposite },
        rows.count { it.returnedOppositeBeforeClose },
        if (rows.isEmpty()) Double.NaN else rows.map { it.left }.average()
    )

    fun report(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>
    ): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.size < 2) return "Too few settled BTC markets for anytime extreme study."
        val discovery = (ordered.size * 7 / 10).coerceIn(1, ordered.lastIndex)
        val holdout = ordered.size - discovery
        val gates = listOf(
            BtcEarlyIndicatorResearchReport.Gate.NONE,
            BtcEarlyIndicatorResearchReport.Gate.MOMENTUM,
            BtcEarlyIndicatorResearchReport.Gate.VOLUME,
            BtcEarlyIndicatorResearchReport.Gate.COUNTER,
            BtcEarlyIndicatorResearchReport.Gate.ACCEL
        )
        return buildString {
            appendLine("=== ANYTIME 90 TOUCH / OPPOSITE-SIDE HISTORY (RESEARCH ONLY) ===")
            appendLine("Markets: ${ordered.size}/1000 | older ${discovery} | newer ${holdout}")
            if (ordered.size < 1000) appendLine("INCOMPLETE: provisional.")
            appendLine("PRIMARY: +90 or -90 at ANY completed 1m candle of a 15m market.")
            appendLine("SECONDARY: +80 or -80, measured identically as a frequency/payout control.")
            appendLine("PRIOR: opposite side >=50, >=80 or >=90 at ANY EARLIER candle in")
            appendLine("the SAME 15m market; does NOT have to be the preceding minute.")
            appendLine("Example: -80 at 14m left and +90 at 3m left QUALIFIES for")
            appendLine("prior opposite 80; +90 before -80 does NOT qualify.")
            appendLine("At the extreme, score whether the OPPOSITE side wins settlement,")
            appendLine("and separately whether the chart touches opposite >=50 BEFORE close.")
            appendLine("Only closed candles determine triggers/indicators; an intraminute")
            appendLine("touch that recovers before candle close is NOT observable here.")
            appendLine("Gates are ALTERNATIVES (unfiltered, momentum, volume, counter-momentum,")
            appendLine("acceleration), not all required together. The full unfiltered counts")
            appendLine("are preserved; gates can trigger at a later qualifying touch.")
            appendLine("Rates are historical BTC settlements, not trade returns.")
            appendLine("Theoretical fee-free breakeven ASK in cents equals measured opposite")
            appendLine("win percentage ONLY if such an executable Kalshi ask actually exists.")
            appendLine("90 here is CUSTOM CHART INDEX, NOT a 90-cent Kalshi contract.")
            appendLine("No actual Kalshi quotes, spread, fees, fills or P&L in this data.")
            appendLine("Older and newer data have been previously researched; newly collected")
            appendLine("markets and real bids/asks are required for independent validation.")
            appendLine("NO MAIN GRAPH, 4 LIVE ALERTS, OR 1400-SCANNER CHANGES.")
            appendLine()


            appendLine("=== MAIN RESULT: EXTREME TOUCH -> LATER OPPOSITE TOUCH -> OPPOSITE SETTLEMENT (CSV) ===")
            appendLine("An upside 90 means first +90 candle at ANY time; subsequent -50-or-lower")
            appendLine("candle at ANY later minute; and BTC ultimately settles DOWN.")
            appendLine("The downside 90 mirror first touches -90, later +50-or-higher,")
            appendLine("and BTC ultimately settles UP. Same definition at 80+; includes 90.")
            appendLine("NO prior opposite touch required. NO fixed gap between readings.")
            appendLine("The combined result counts distinct markets, not two alerts in one market.")
            appendLine("Also report opposite settlement alone, in case the flip occurs only")
            appendLine("in the final minute that this closed-candle chart does not observe.")
            appendLine("touch_hit_pct = touching / 1000; complete_flip_market_pct = flip-and-settle / 1000.")
            appendLine("complete_flip_given_touch_pct = flip-and-settle / touching.")
            appendLine("FIRST_TOUCH_ONLY is the earliest extreme of EITHER side in each")
            appendLine("market, so it cannot benefit from choosing the better side afterward.")
            appendLine("The original 1000-market data cannot prove executable Kalshi returns.")
            appendLine()
            appendLine("threshold,side,markets_examined,markets_touching,touch_hit_pct," +
                "markets_settling_opposite,settle_opposite_given_touch_pct," +
                "markets_touching_opposite_later,markets_flipping_AND_settling_opposite," +
                "complete_flip_given_touch_pct,complete_flip_market_pct,mean_minutes_left_at_touch")
            for (threshold in listOf(90, 80)) {
                val plus = flipEvents(ordered, threshold, true)
                val minus = flipEvents(ordered, threshold, false)
                val combinedByMarket = (plus + minus).groupBy { it.marketIndex }
                val firstOnly = combinedByMarket.values.map { events ->
                    events.minBy { it.firstMinute }
                }
                // Opposite settlement can succeed on only one side, so the
                // union is a distinct-market indicator, never summed blindly.
                val uniqueCombined = combinedByMarket.values.map { events ->
                    events.firstOrNull { it.fullyFlippedAndSettled }
                        ?: events.firstOrNull { it.settledOpposite }
                        ?: events.minBy { it.firstMinute }
                }
                flipSummaryLine(threshold, "UP_TOUCH_then_DOWN", plus, ordered.size)
                flipSummaryLine(threshold, "DOWN_TOUCH_then_UP", minus, ordered.size)
                flipSummaryLine(threshold, "COMBINED_UNIQUE_MARKETS", uniqueCombined, ordered.size)
                flipSummaryLine(threshold, "FIRST_EXTREME_ONLY", firstOnly, ordered.size)
                for ((part, predicate, total) in listOf(
                    Triple("OLDER_70PCT", { index: Int -> index < discovery }, discovery),
                    Triple("NEWER_30PCT", { index: Int -> index >= discovery }, holdout)
                )) {
                    flipSummaryLine(threshold, part + "_UP_TOUCH_then_DOWN",
                        plus.filter { predicate(it.marketIndex) }, total)
                    flipSummaryLine(threshold, part + "_DOWN_TOUCH_then_UP",
                        minus.filter { predicate(it.marketIndex) }, total)
                    flipSummaryLine(threshold, part + "_COMBINED_UNIQUE",
                        uniqueCombined.filter { predicate(it.marketIndex) }, total)
                    flipSummaryLine(threshold, part + "_FIRST_EXTREME_ONLY",
                        firstOnly.filter { predicate(it.marketIndex) }, total)
                }
            }
            appendLine()
            appendLine("=== 90-FIRST-TOUCH FREQUENCY BY EXACT MINUTE (CSV) ===")
            appendLine("extreme,at_minutes_left,markets_touching_at_minute,first_touch_at_minute," +
                "cumulative_markets_touched_by_minute,cumulative_pct_of_all_markets")
            for (extreme in listOf(90, -90)) {
                val perMarketTouches = ordered.map { market ->
                    market.minutes.filter { it.minute in 1..14 }
                        .mapNotNull { obs ->
                            if (level(obs.signedChartLevel) == extreme) obs.minute else null
                        }.distinct().sorted()
                }
                for (minute in 1..14) {
                    val anyAt = perMarketTouches.count { minute in it }
                    val firstAt = perMarketTouches.count { it.firstOrNull() == minute }
                    val cumulative = perMarketTouches.count { it.firstOrNull()?.let { first -> first <= minute } == true }
                    appendLine("${if (extreme > 0) "+" else ""}${extreme},${15 - minute}," +
                        "${anyAt},${firstAt},${cumulative},${fmt(100.0 * cumulative / ordered.size)}")
                }
            }
            appendLine()
            appendLine("=== ANYTIME HISTORY AND INDICATORS: SETTLEMENT VS RETURN (CSV) ===")
            appendLine("extreme,prior,buy_side,filter,older_hits,older_hit_pct,older_opposite_wins," +
                "older_opposite_win_pct,newer_hits,newer_hit_pct,newer_opposite_wins," +
                "newer_opposite_win_pct,newer_wilson_lower_95_pct," +
                "newer_return_to_opposite_pct,newer_mean_min_left," +
                "all_hits,retained_vs_unfiltered_pct,newer_theoretical_break_even_ask_cents_before_fees")
            for (query in queries) {
                val raw = hits(ordered, features, query, BtcEarlyIndicatorResearchReport.Gate.NONE)
                for (gate in gates) {
                    val row = if (gate == BtcEarlyIndicatorResearchReport.Gate.NONE)
                        raw else hits(ordered, features, query, gate)
                    val older = stats(row.filter { it.marketIndex < discovery })
                    val newer = stats(row.filter { it.marketIndex >= discovery })
                    appendLine("${query.label},${query.prior.label},${query.side},${gate.label}," +
                        "${older.count},${fmt(100.0 * older.count / discovery)}," +
                        "${older.wins},${fmt(older.winPct)}," +
                        "${newer.count},${fmt(100.0 * newer.count / holdout)}," +
                        "${newer.wins},${fmt(newer.winPct)},${fmt(newer.wilsonLow)}," +
                        "${fmt(newer.returnPct)},${fmt(newer.meanLeft)}," +
                        "${row.size},${fmt(if (raw.isEmpty()) Double.NaN else 100.0 * row.size / raw.size)}," +
                        "${fmt(newer.winPct)}")
                }
            }
            appendLine()
            appendLine("=== PRIMARY 90 RESULTS BY REMAINING-TIME WINDOW (CSV) ===")
            appendLine("extreme,prior,buy_side,minutes_left_window,older_hits,older_win_pct," +
                "newer_hits,newer_win_pct,newer_return_opposite_pct")
            val windows = listOf(11..14, 7..10, 3..6, 1..2)
            for (query in queries.filter { kotlin.math.abs(it.extreme) == 90 }) {
                val all = hits(ordered, features, query,
                    BtcEarlyIndicatorResearchReport.Gate.NONE)
                for (window in windows) {
                    val older = stats(all.filter { it.marketIndex < discovery && it.left in window })
                    val newer = stats(all.filter { it.marketIndex >= discovery && it.left in window })
                    appendLine("${query.label},${query.prior.label},${query.side}," +
                        "${window.first}-${window.last},${older.count},${fmt(older.winPct)}," +
                        "${newer.count},${fmt(newer.winPct)},${fmt(newer.returnPct)}")
                }
            }
            appendLine("Use net EV per original market, not hit-rate or payout alone;")
            appendLine("small sample sizes are not sufficient evidence of positive EV.")
            appendLine("=== END ANYTIME EXTREME RESEARCH ===")
        }
    }
}
