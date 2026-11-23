package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.floor

/**
 * RESEARCH ONLY. The 27 fixed sequences were named in 10-point steps.
 * For BINANCE BTC spot, we measure the app's signed distance-to-target
 * chart proxy, NOT Kalshi YES odds or any Kalshi quote.
 *
 * Above BTC target: +60/+70/+80/+90 -> step 60/70/80/90.
 * Below BTC target: -60/-70/-80/-90 -> step 40/30/20/10.
 * Within the 50-59 chart-magnitude region on EITHER side -> step 50.
 *
 * The mapping is explicit so users cannot mistake these hit/win rates
 * for results from Kalshi probabilities.
 */
object BinanceTwentySevenPatternReport {
    data class Observation(val minute: Int, val signedChartLevel: Double,
        val low: Double = Double.NaN, val high: Double = Double.NaN)
    data class Market(
        val startMs: Long,
        val targetBtc: Double,
        val finalBtc: Double,
        val minutes: List<Observation>
    ) {
        val upWon: Boolean get() = finalBtc > targetBtc
    }
    data class Rule(val steps: List<Int>, val buyUp: Boolean) {
        val label: String get() = steps.joinToString(" -> ") { "$it%" }
        val direction: String get() = if (buyUp) "UP" else "DOWN"
    }

    // LOCKED 27 paths from the user's original list. Do not data-mine new
    // candidates or change the rule list after seeing these 1,000 markets.
    val rules: List<Rule> = listOf(
        Rule(listOf(40,50),false), Rule(listOf(30,40),true),
        Rule(listOf(60,50),false), Rule(listOf(50,40,50),false),
        Rule(listOf(40,30,40),true), Rule(listOf(50,60,50),false),
        Rule(listOf(40,60,50),false), Rule(listOf(70,60),false),
        Rule(listOf(20,40),true), Rule(listOf(80,60),false),
        Rule(listOf(70,50),false), Rule(listOf(40,20,40),true),
        Rule(listOf(50,30,40),true), Rule(listOf(30,20,40),true),
        Rule(listOf(30,50,40),true), Rule(listOf(10,50),false),
        Rule(listOf(30,20,50),false), Rule(listOf(40,20,50),false),
        Rule(listOf(50,20,40),true), Rule(listOf(30,10,50),false),
        Rule(listOf(40,10,50),false), Rule(listOf(50,70,50),false),
        Rule(listOf(60,70,50),false), Rule(listOf(10,40),true),
        Rule(listOf(20,10,50),false), Rule(listOf(20,30,50),false),
        Rule(listOf(50,70,60),false)
    )

    /** Market starts at 10 AM or later, and FINISHES by 11 PM Eastern. */
    fun isAllowedSession(startMs: Long): Boolean {
        val zone = ZoneId.of("America/New_York")
        val start = Instant.ofEpochMilli(startMs).atZone(zone)
        val end = Instant.ofEpochMilli(startMs + 15L * 60_000L).atZone(zone)
        return start.toLocalDate() == end.toLocalDate() &&
            !start.toLocalTime().isBefore(java.time.LocalTime.of(10, 0)) &&
            !end.toLocalTime().isAfter(java.time.LocalTime.of(23, 0))
    }

    internal data class Hit(val left: Int, val won: Boolean)
    internal data class Window(val label: String, val high: Int, val low: Int) {
        fun includes(left: Int) = left in low..high
    }
    internal val windows = listOf(Window("11-9m", 11, 9),
        Window("8-5m", 8, 5), Window("4-1m", 4, 1))

    /**
     * Exactly 10 bands on the signed proxy. Values are not Kalshi odds.
     * 50 is the zone near the target on either side.
     */
    internal fun band(signedLevel: Double): Int {
        if (!signedLevel.isFinite()) return -1
        if (signedLevel == 0.0) return 50
        val magnitude = kotlin.math.abs(signedLevel)
        if (magnitude < 50.0) return -1
        val bucket = (floor(magnitude / 10.0).toInt() * 10).coerceIn(50, 90)
        return if (bucket == 50) 50 else if (signedLevel > 0.0)
            bucket else 100 - bucket
    }

    /**
     * Earlier stages may occur in the first three minutes, but the first
     * complete sequence must finish in minutes 4..14 of the 15m market.
     * Later rebounds after an early completion do not retroactively create
     * a new first signal. Distinct minute closes and same sequence order.
     */
    internal fun firstHit(market: Market, rule: Rule): Hit? {
        val sorted = market.minutes.filter { it.minute in 1..14 }
            .sortedBy { it.minute }.distinctBy { it.minute }
        var step = 0
        for (m in sorted) {
            if (band(m.signedChartLevel) != rule.steps[step]) continue
            step++
            if (step == rule.steps.size) {
                val left = 15 - m.minute
                return if (left in 1..11)
                    Hit(left, if (rule.buyUp) market.upWon else !market.upWon)
                    else null
            }
        }
        return null
    }

    private data class Summary(val rule: Rule, val hits: List<Hit>, val total: Int) {
        val count get() = hits.size
        val wins get() = hits.count { it.won }
        val hitRate get() = if (total == 0) 0.0 else 100.0 * count / total
        val winRate get() = if (count == 0) Double.NaN else 100.0 * wins / count
        val breakdown get() = windows.map { window ->
            window to hits.filter { window.includes(it.left) }
        }
        val mostCommon get() = breakdown.maxWithOrNull(
            compareBy<Pair<Window, List<Hit>>> { it.second.size }
                .thenBy { it.first.high })
    }
    private fun n(x: Double): String =
        if (!x.isFinite()) "n/a" else String.format(Locale.US, "%.1f", x)

    fun report(markets: List<Market>, skipped: Int = 0): String {
        val unique = markets.distinctBy { it.startMs }
            .sortedBy { it.startMs }
        if (unique.isEmpty()) return "No complete eligible Binance BTC sections."
        val total = unique.size
        val results = rules.map { rule ->
            Summary(rule, unique.mapNotNull { firstHit(it, rule) }, total)
        }.sortedWith(compareByDescending<Summary> { it.count }
            .thenByDescending { it.winRate }.thenBy { it.rule.label })
        val et = ZoneId.of("America/New_York")
        fun timestamp(ms: Long) = Instant.ofEpochMilli(ms).atZone(et).toString()
        val sb = StringBuilder(20000)
        sb.appendLine("=== BINANCE BTC 27 FIXED PATTERNS - 1000 MARKET BACKTEST ===")
        sb.appendLine("SOURCE: Binance.US BTCUSDT actual 1-minute OHLC candles, NOT Kalshi.")
        sb.appendLine("Eligible complete 15-minute markets: $total/1000 | skipped: $skipped")
        if (total != 1000) sb.appendLine("INCOMPLETE: fewer than 1,000 qualifying sections.")
        sb.appendLine("Session: starts >=10:00 AM and ends <=11:00 PM America/New_York.")
        sb.appendLine("Validation markets strictly BEFORE 2026-11-17 Eastern; Nov 17-19 discovery markets excluded.")
        sb.appendLine("First market: " + timestamp(unique.first().startMs))
        sb.appendLine("Last market: " + timestamp(unique.last().startMs))
        sb.appendLine("Market target = midpoint of the final Binance BTC one-minute high and low before the quarter-hour.")
        sb.appendLine("UP win = final 15-minute BTC close ABOVE target; " +
            "DOWN win = close BELOW target. Ties skipped.")
        sb.appendLine("Pattern levels = app's BINANCE signed distance/ACM60 chart: " +
            "10=-90, 20=-80, 30=-70, 40=-60, 50=near target, " +
            "60=+60, 70=+70, 80=+80, 90=+90.")
        sb.appendLine("These are NOT Kalshi YES percentage prices; " +
            "the 27 names are transferred to this new Binance mapping.")
        sb.appendLine("Each chart level calculated only from completed BTC candles " +
            "and the 60 most recent completed 1-minute candle ranges.")
        sb.appendLine("One earliest ordered complete match per pattern per market. " +
            "M01-M03 may start patterns, only M04-M14 finish. " +
            "M15 is settlement, never an entry.")
        sb.appendLine("Hit rate = matched distinct markets / $total; " +
            "win rate = BUY-side wins / matches.")
        sb.appendLine("Most-common time = window with most matching markets, " +
            "NOT the retrospectively highest win rate.")
        sb.appendLine("Fixed windows: 11-9, 8-5, 4-1 minutes left.")
        sb.appendLine("ALL 27 results included, even when zero matches. " +
            "No filtering, profitability calculations or trade execution.")
        sb.appendLine()
        sb.appendLine("rank,pattern,buy,hits,hit_rate_pct,wins,win_rate_pct," +
            "most_common_time_left,most_common_hits")
        for ((i, r) in results.withIndex()) {
            val common = r.mostCommon
            sb.appendLine(listOf(i + 1, r.rule.label, r.rule.direction,
                r.count, n(r.hitRate), r.wins, n(r.winRate),
                if (r.count == 0) "n/a" else common?.first?.label ?: "n/a",
                if (r.count == 0) 0 else common?.second?.size ?: 0
            ).joinToString(","))
        }
        sb.appendLine()
        sb.appendLine("=== EXACT COUNTS BY TIME LEFT ===")
        sb.appendLine("pattern,buy,time_left,hits,wins,hit_rate_pct,win_rate_pct")
        for (r in results) for ((window, hits) in r.breakdown) {
            val w = hits.count { it.won }
            sb.appendLine(listOf(r.rule.label, r.rule.direction, window.label,
                hits.size, w, n(100.0 * hits.size / total),
                if (hits.isEmpty()) "n/a" else n(100.0 * w / hits.size)
            ).joinToString(","))
        }
        sb.appendLine("=== END BINANCE 1000 MARKET REPORT ===")
        return sb.toString()
    }
}
