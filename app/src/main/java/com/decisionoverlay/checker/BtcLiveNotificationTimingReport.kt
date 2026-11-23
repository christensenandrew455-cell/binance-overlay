package com.decisionoverlay.checker

import java.util.Locale

/**
 * The EXACT four patterns and exact signed-index binning read by
 * KalshiLiveSignalService from BtcRangeBacktestService's closed-minute chart.
 *
 * Counts BOTH (a) raw occurrences at each finish minute and (b) what the
 * current frozen KalshiLiveSignalRules would have notified at that minute
 * if the chart service had delivered each completed 1m snapshot promptly.
 *
 * Uses frozen live-window minimum 75% win rate, inclusive, and >=20 older cases.\n * Reports actual hit/win timing; no further cutoffs selected here.
 */
internal object BtcLiveNotificationTimingReport {
    internal data class Count(var hits: Int = 0, var wins: Int = 0) {
        val winPct: Double get() =
            if (hits == 0) Double.NaN else 100.0 * wins / hits
        fun add(won: Boolean) {
            hits++
            if (won) wins++
        }
    }

    internal class SplitCount {
        val all = Count()
        val older = Count()
        val newer = Count()
        fun add(won: Boolean, marketIndex: Int, split: Int) {
            all.add(won)
            if (marketIndex < split) older.add(won) else newer.add(won)
        }
    }

    internal data class Key(val rule: KalshiLiveSignalRules.Rule, val finishLeft: Int)
    internal data class Row(
        val key: Key,
        val firstLeft: Int?,
        val matched: SplitCount = SplitCount(),
        val eligibleMatch: SplitCount = SplitCount(),
        val notified: SplitCount = SplitCount()
    )
    internal data class Result(
        val total: Int,
        val older: Int,
        val newer: Int,
        val rows: List<Row>,
        val uniqueNotified: SplitCount
    ) {
        fun row(
            fromBand: Int, toBand: Int, gap: Int, finishLeft: Int
        ): Row = rows.single {
            it.key.rule.fromBand == fromBand &&
                it.key.rule.toBand == toBand &&
                it.key.rule.gapMinutes == gap &&
                it.key.finishLeft == finishLeft
        }
    }

    /**
     * A live monitor sees the chart history one CLOSED minute at a time.
     * Use the SAME bin comparator, four rules, frozen exact-time eligibility
     * and one-notification-per-market priority as the real live service.
     * Network delays, missed polls and intraminute price recrosses cannot be
     * reconstructed from historical closed candles, so sent counts are ideal.
     */
    internal fun analyze(
        markets: List<BinanceTwentySevenPatternReport.Market>
    ): Result {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        val total = ordered.size
        val split = if (total <= 1) total else (total * 7 / 10).coerceIn(1, total - 1)
        val rules = KalshiLiveSignalRules.rules
        val rowMap = linkedMapOf<Key, Row>()
        for (rule in rules) {
            for (left in 14 downTo 1) {
                val first = left + rule.gapMinutes
                val key = Key(rule, left)
                rowMap[key] = Row(key, first.takeIf { it in 1..14 })
            }
        }
        val anyNotifications = SplitCount()
        ordered.forEachIndexed { marketIndex, market ->
            val history = market.minutes.asSequence()
                .filter { it.minute in 1..14 && it.signedChartLevel.isFinite() }
                .sortedBy { it.minute }.distinctBy { it.minute }
                .map { BtcChartProxyMath.Sample(15 - it.minute, it.signedChartLevel) }
                .toList()
            val soFar = ArrayList<BtcChartProxyMath.Sample>(history.size)
            val byLeft = HashMap<Int, BtcChartProxyMath.Sample>()
            var sent = false
            for (current in history) {
                byLeft[current.left] = current
                soFar.add(current)
                for (rule in rules) {
                    val startLeft = current.left + rule.gapMinutes
                    val before = byLeft[startLeft] ?: continue
                    if (BtcExhaustivePatternReport.band(before.signedLevel) != rule.fromBand ||
                        BtcExhaustivePatternReport.band(current.signedLevel) != rule.toBand
                    ) continue
                    val row = rowMap.getValue(Key(rule, current.left))
                    val won = market.upWon == rule.buyUp
                    row.matched.add(won, marketIndex, split)
                    // This is the CURRENT frozen eligibility (>=20 discovery
                    // cases and >15 index-point gap), not a new proposed cutoff.
                    if (KalshiLiveSignalRules.windowFor(rule, startLeft, current.left)
                            ?.eligible == true) {
                        row.eligibleMatch.add(won, marketIndex, split)
                    }
                }
                if (sent) continue
                // The real monitor chooses the best qualified exact-time rule
                // and only sends ONE notification in the whole 15m market.
                val firstAlert = KalshiLiveSignalRules
                    .matchingSignedSignals(soFar).firstOrNull() ?: continue
                val won = market.upWon == firstAlert.rule.buyUp
                rowMap.getValue(Key(firstAlert.rule, current.left)).notified
                    .add(won, marketIndex, split)
                anyNotifications.add(won, marketIndex, split)
                sent = true
            }
        }
        return Result(total, split, total - split, rowMap.values.toList(),
            anyNotifications)
    }

    private fun fmt(v: Double) = if (!v.isFinite()) "N/A"
        else String.format(Locale.US, "%.1f", v)
    private fun percent(n: Int, total: Int) =
        if (total == 0) "N/A" else fmt(100.0 * n / total)
    private fun label(n: Int) = (if (n > 0) "+" else "") + n

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        val a = analyze(markets)
        if (a.total == 0) return "No completed historical BTC markets to test."
        return buildString {
            appendLine("=== EXACT LIVE GRAPH ALERT LEVELS: HIT AND WIN RATE BY MINUTE ===")
            appendLine("Completed BTC markets: " + a.total + "/1000; older " +
                a.older + "; newer " + a.newer + ".")
            if (a.total != 1000) appendLine("INCOMPLETE SAMPLE: figures provisional.")
            appendLine("SOURCE: SAME BtcChartProxyMath.signedStrength, signed")
            appendLine("10-point INDEX bands, and four +/-50 -> +/-60 in 1m/2m")
            appendLine("paths that the live BTC graph-notification logic evaluates.")
            appendLine("These 50/60 values are CUSTOM CHART LEVELS, not Kalshi")
            appendLine("contract prices, calibrated probabilities or cash returns.")
            appendLine("TIME = exact minutes left WHEN destination candle CLOSES.")
            appendLine("First minute = finish minute + 1 or + 2, respectively.")
            appendLine("HIT RATE AT TIME = matching distinct markets / " + a.total + ".")
            appendLine("WIN RATE AT TIME = markets settling in notification side / matches.")
            appendLine("NO MATCHES => win rate N/A, NOT 0%.")
            appendLine("Same market may appear at multiple different minutes/patterns;")
            appendLine("do not ADD raw hit rates and assume they are independent.")
            appendLine("Older/newer are separated; newer was previously researched,")
            appendLine("so this is NOT a pristine out-of-sample performance claim.")
            appendLine("Eligible_match = matches satisfying the EXISTING frozen live")
            appendLine("window gate: >=20 older hits and >=15 win-rate points above 60.")
            appendLine("First_notification = IDEAL replay of EXISTING live alerts,")
            appendLine("one chosen notification per 15-minute market, first eligible")
            appendLine("minute and highest rule priority at ties. Actual phone polls,")
            appendLine("late snapshots and intraminute price crossing are not modeled.")
            appendLine("EXACT-TIME 75% WIN-RATE MINIMUM APPLIED IN LIVE ELIGIBILITY.")
            appendLine("NO CHART CHANGES; preserve minimum 20 older matches.")
            appendLine()

            appendLine("=== GRAPH DATA: EACH LIVE PATTERN AT EVERY EXACT FINISH MINUTE (CSV) ===")
            appendLine("from_chart,to_chart,gap_minutes,notify_side,first_minutes_left," +
                "finish_minutes_left,hit_markets,hit_rate_pct_of_all_markets," +
                "wins,win_rate_pct,older_hits,older_hit_pct_of_older_markets," +
                "older_wins,older_win_rate_pct,newer_hits," +
                "newer_hit_pct_of_newer_markets,newer_wins,newer_win_rate_pct," +
                "current_live_window_eligible,eligible_matches,eligible_wins," +
                "first_notifications,first_notification_hit_rate_pct," +
                "first_notification_wins,first_notification_win_rate_pct," +
                "older_first_notifications,older_first_wins," +
                "newer_first_notifications,newer_first_wins")
            for (row in a.rows) {
                val rule = row.key.rule
                val all = row.matched
                val notified = row.notified
                val eligible = row.firstLeft?.let {
                    KalshiLiveSignalRules.windowFor(rule, it, row.key.finishLeft)
                        ?.eligible == true
                } ?: false
                appendLine(listOf(
                    label(rule.fromBand), label(rule.toBand),
                    rule.gapMinutes.toString(), rule.side,
                    row.firstLeft?.toString() ?: "N/A",
                    row.key.finishLeft.toString(),
                    all.all.hits.toString(),
                    percent(all.all.hits, a.total),
                    all.all.wins.toString(), fmt(all.all.winPct),
                    all.older.hits.toString(), percent(all.older.hits, a.older),
                    all.older.wins.toString(), fmt(all.older.winPct),
                    all.newer.hits.toString(), percent(all.newer.hits, a.newer),
                    all.newer.wins.toString(), fmt(all.newer.winPct),
                    eligible.toString(),
                    row.eligibleMatch.all.hits.toString(),
                    row.eligibleMatch.all.wins.toString(),
                    notified.all.hits.toString(),
                    percent(notified.all.hits, a.total),
                    notified.all.wins.toString(), fmt(notified.all.winPct),
                    notified.older.hits.toString(), notified.older.wins.toString(),
                    notified.newer.hits.toString(), notified.newer.wins.toString()
                ).joinToString(","))
            }
            appendLine()

            appendLine("=== IDEAL LIVE NOTIFICATION REPLAY, ALL FOUR PATTERNS COMBINED ===")
            appendLine("Unique markets with first eligible notification: " +
                a.uniqueNotified.all.hits + "/" + a.total + " (" +
                percent(a.uniqueNotified.all.hits, a.total) + "%).")
            appendLine("Correct notified settlement side: " +
                a.uniqueNotified.all.wins + "/" +
                a.uniqueNotified.all.hits + " (" +
                fmt(a.uniqueNotified.all.winPct) + "%).")
            appendLine("Older: " + a.uniqueNotified.older.hits + "/" + a.older +
                " notified markets, " + a.uniqueNotified.older.wins +
                " winning, " + fmt(a.uniqueNotified.older.winPct) + "% win.")
            appendLine("Newer: " + a.uniqueNotified.newer.hits + "/" + a.newer +
                " notified markets, " + a.uniqueNotified.newer.wins +
                " winning, " + fmt(a.uniqueNotified.newer.winPct) + "% win.")
            appendLine("Historical settlement outcomes are NOT actual Kalshi")
            appendLine("executed orders or expected profit after quotes/fees.")
            appendLine("=== END LIVE GRAPH ALERT TIMING ===")
        }
    }
}
