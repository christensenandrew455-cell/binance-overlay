package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.sqrt

/** 100-day timing study of the four EXISTING signed-band live BUY rules. */
internal object BtcHourTimingReport {
    private val zone = ZoneId.of("America/New_York")
    private data class Signal(val start: Long, val hour: Int, val left: Int,
        val side: String, val gap: Int, val won: Boolean)
    private fun pct(a: Int, b: Int): String =
        if (b == 0) "" else String.format(Locale.US, "%.2f", 100.0 * a / b)
    private fun lower(w: Int, n: Int): String {
        if (n == 0) return ""
        val p = w.toDouble() / n
        val z = 1.96
        val d = 1 + z * z / n
        return String.format(Locale.US, "%.2f",
            100 * (p + z * z / (2 * n) -
                z * sqrt((p * (1 - p) + z * z / (4 * n)) / n)) / d)
    }

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        // Match the exact completed-candle band definitions used by live alerts.
        // One earliest completed match PER RULE PER MARKET. Never reverse BUY side.
        val signals = ordered.flatMap { market ->
            val byMinute = market.minutes.filter { it.minute in 1..14 }
                .sortedBy { it.minute }.distinctBy { it.minute }
                .associateBy { it.minute }
            KalshiLiveSignalRules.rules.mapNotNull { rule ->
                val hit = (1..14).firstOrNull { finish ->
                    val start = finish - rule.gapMinutes
                    if (start < 1) return@firstOrNull false
                    val from = byMinute[start]?.signedChartLevel ?: Double.NaN
                    val to = byMinute[finish]?.signedChartLevel ?: Double.NaN
                    BtcExhaustivePatternReport.band(from) == rule.fromBand &&
                        BtcExhaustivePatternReport.band(to) == rule.toBand
                } ?: return@mapNotNull null
                Signal(market.startMs,
                    Instant.ofEpochMilli(market.startMs).atZone(zone).hour,
                    15 - hit, rule.side, rule.gapMinutes,
                    if (rule.buyUp) market.upWon else !market.upWon)
            }
        }
        val cut = ordered.size * 70 / 100
        val splits = listOf("older_discovery" to ordered.take(cut),
            "newer_check" to ordered.drop(cut),
            "all_descriptive" to ordered)
        return buildString {
            appendLine("split,market_start_hour_ET,minutes_left,side,gap_minutes,eligible_markets,alerts,hit_rate_pct,wins,losses,win_rate_pct,wilson95_lower_pct,first_market_ET,last_market_ET,notes")
            for ((split, sample) in splits) {
                val starts = sample.map { it.startMs }.toHashSet()
                val subset = signals.filter { it.start in starts }
                val first = sample.firstOrNull()?.let {
                    Instant.ofEpochMilli(it.startMs).atZone(zone).toString()
                }.orEmpty()
                val last = sample.lastOrNull()?.let {
                    Instant.ofEpochMilli(it.startMs).atZone(zone).toString()
                }.orEmpty()
                for (hour in listOf(-1) + (10..22).toList()) {
                    for (left in listOf(-1) + (14 downTo 1).toList()) {
                        for (rule in KalshiLiveSignalRules.rules) {
                            val eligible = sample.count {
                                hour < 0 || Instant.ofEpochMilli(it.startMs)
                                    .atZone(zone).hour == hour
                            }
                            val matches = subset.filter {
                                (hour < 0 || it.hour == hour) &&
                                    (left < 0 || it.left == left) &&
                                    it.side == rule.side && it.gap == rule.gapMinutes
                            }
                            val wins = matches.count { it.won }
                            appendLine(listOf(split,
                                if (hour < 0) "ALL" else hour,
                                if (left < 0) "ALL" else left,
                                rule.side, rule.gapMinutes, eligible, matches.size,
                                pct(matches.size, eligible), wins, matches.size - wins,
                                pct(wins, matches.size), lower(wins, matches.size),
                                first, last,
                                "exact_signed_50_to_60;completed_candles;first_per_rule_per_market;BTC_chart_proxy_not_Kalshi_odds"
                            ).joinToString(","))
                        }
                    }
                }
            }
        }
    }
}
