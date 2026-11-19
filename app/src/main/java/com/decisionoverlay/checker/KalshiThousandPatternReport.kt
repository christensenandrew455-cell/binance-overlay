package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * 27 LOCKED rules from a separate 100-market discovery study.
 * Match ordered EXACT ten-point YES price bands, not thresholds.
 */
object KalshiThousandPatternReport {
    internal data class Rule(val steps: List<Int>, val buyUp: Boolean) {
        val label get() = steps.joinToString(" -> ") { it.toString() + "%" }
        val direction get() = if (buyUp) "UP" else "DOWN"
    }
    val rules = listOf(
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
    internal data class Hit(val left: Int, val won: Boolean)
    private data class Window(val name: String, val high: Int, val low: Int) {
        fun contains(left: Int) = left in low..high
    }
    private val windows = listOf(Window("11-9",11,9),
        Window("8-5",8,5), Window("4-1",4,1))

    /** Earliest complete ordered match in the MARKET; count it once.
     * M01-M03 can form earlier stages, but completion only M04..M14 is
     * trade-eligible. M15 = 0 left and is never an entry.
     */
    internal fun firstHit(
        market: KalshiHundredMarketReport.Market, rule: Rule
    ): Hit? {
        val minutes = market.minutes.filter { it.minute in 1..14 &&
            it.avgYesPercent.isFinite() && it.avgYesPercent in 0.0..100.0 }
            .sortedBy { it.minute }.distinctBy { it.minute }
        var nextStep = 0
        for (m in minutes) {
            if (KalshiHundredMarketReport.band(m.avgYesPercent) == rule.steps[nextStep]) {
                nextStep++
                if (nextStep == rule.steps.size) {
                    val left = 15 - m.minute
                    return if (left in 1..11)
                        Hit(left, if (rule.buyUp) market.yesWon else !market.yesWon)
                    else null
                }
            }
        }
        return null
    }

    private data class Result(val rule: Rule, val hits: List<Hit>, val total: Int) {
        val count get() = hits.size
        val wins get() = hits.count { it.won }
        val hitPct get() = if (total == 0) 0.0 else 100.0 * count / total
        val wr get() = if (count == 0) 0.0 else 100.0 * wins / count
        val windowData get() = windows.map { it to hits.filter { hit -> it.contains(hit.left) } }
        // Most frequent time band, not retrospectively max WR.
        val common get() = windowData.maxWithOrNull(
            compareBy<Pair<Window,List<Hit>>> { it.second.size }.thenBy { it.first.high })
        // Descriptive only, min 10 hits prevents tiny 1/1 "100%" rows.
        val bestWin get() = windowData.filter { it.second.size >= 10 }
            .maxWithOrNull(compareBy<Pair<Window,List<Hit>>> {
                it.second.count { h -> h.won }.toDouble() / it.second.size
            }.thenBy { it.second.size })
    }
    private fun p(d: Double) = String.format(Locale.US, "%.1f", d)

    fun report(markets: List<KalshiHundredMarketReport.Market>, skipped: Int = 0): String {
        val ordered = markets.distinctBy { it.ticker }
            .filter { KalshiHundredMarketReport.isAllowedSession(it.closeTs) }
            .sortedBy { it.closeTs }
        if (ordered.isEmpty()) return "No settled BTC validation markets available."
        val results = rules.map { r ->
            Result(r, ordered.mapNotNull { firstHit(it,r) }, ordered.size)
        }.sortedWith(compareByDescending<Result> { it.count }
            .thenByDescending { it.wr }.thenBy { it.rule.label })
        val sb = StringBuilder(16000)
        sb.appendLine("=== 27 LOCKED BTC PATTERNS - 1000 MARKET VALIDATION ===")
        sb.appendLine("Distinct settled BTC markets: " + ordered.size + "/1000; skipped: " + skipped)
        if (ordered.size != 1000) sb.appendLine("INCOMPLETE: provisional, not a finished 1000-market test.")
        sb.appendLine("First close Eastern: " +
            Instant.ofEpochSecond(ordered.first().closeTs).atZone(ZoneId.of("America/New_York")))
        sb.appendLine("Last close Eastern: " +
            Instant.ofEpochSecond(ordered.last().closeTs).atZone(ZoneId.of("America/New_York")))
        sb.appendLine("Validation ends BEFORE 2026-11-17 Eastern, excluding all Nov 17-19 discovery markets.")
        sb.appendLine("27 predefined ordered exact 10-point YES price bands (e.g. 40 = 40-49.999%).")
        sb.appendLine("Stages occur in different observed minutes; gaps allowed. First COMPLETE match only per rule/market.")
        sb.appendLine("Only completions M04-M14 = 11..1 minutes LEFT; first 3 minutes research only; M15/settlement excluded.")
        sb.appendLine("Hit rate = distinct matching markets / all tested markets. WR = predicted UP/DOWN settlement wins / matches.")
        sb.appendLine("Common time is the highest-count fixed window; best-WR time requires at least 10 hits and is descriptive.")
        sb.appendLine("Time windows FIXED before validation: 11-9, 8-5, 4-1 minutes left.")
        sb.appendLine("All 27 shown, sorted by hit count; overlapping paths aren't independent. No fee/ask or live fill assumed.")
        sb.appendLine()
        sb.appendLine("rank,pattern,buy,hits,hit_rate_pct,wins,win_rate_pct,common_time_left,common_hits,best_WR_time_left_ge10,best_time_hits,best_time_WR_pct")
        results.forEachIndexed { i,r ->
            val c = r.common
            val b = r.bestWin
            sb.appendLine(listOf(
                i+1,r.rule.label,r.rule.direction,r.count,p(r.hitPct),r.wins,p(r.wr),
                c?.first?.name ?: "n/a",c?.second?.size ?: 0,
                b?.first?.name ?: "n/a",b?.second?.size ?: 0,
                if (b == null) "n/a" else p(100.0*b.second.count { it.won }/b.second.size)
            ).joinToString(","))
        }
        sb.appendLine()
        sb.appendLine("=== FREQUENT: STRICTLY ABOVE 10% HIT RATE ===")
        val frequent = results.filter { it.hitPct > 10.0 }
        if (frequent.isEmpty()) sb.appendLine("None qualify.")
        for (r in frequent) sb.appendLine(r.rule.label + " | BUY " + r.rule.direction +
            " | Hit " + p(r.hitPct) + "% (" + r.count + "/" + ordered.size +
            ") | WR " + p(r.wr) + "% (" + r.wins + "/" + r.count +
            ") | Most common " + (r.common?.first?.name ?: "n/a") + " min left")
        sb.appendLine()
        sb.appendLine("=== ALL PATTERNS BY FIXED TIMEFRAME ===")
        sb.appendLine("pattern,buy,time_left,hits,wins,hit_pct_of_all_markets,win_rate_pct")
        for (r in results) for ((window,hits) in r.windowData)
            sb.appendLine(listOf(r.rule.label,r.rule.direction,window.name,
                hits.size,hits.count { it.won },p(100.0*hits.size/ordered.size),
                if (hits.isEmpty()) "n/a" else p(100.0*hits.count { it.won }/hits.size)
            ).joinToString(","))
        sb.appendLine("END. Historical WIN RATE does not prove an edge over actual ask prices plus fees.")
        return sb.toString()
    }
}
