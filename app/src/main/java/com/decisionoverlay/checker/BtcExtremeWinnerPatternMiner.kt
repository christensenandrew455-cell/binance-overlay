package com.decisionoverlay.checker

import java.time.Instant
import java.util.Locale
import kotlin.math.sqrt

/**
 * WINNER-FIRST RESEARCH, NOT AN ALERT STRATEGY.
 *
 * For each +90/-90 and +80/-80 touch, identify the markets which later
 * SETTLED on the opposite side. Inspect ONLY readings before that touch,
 * plus indicators available at the closed touch candle. Contrast the winners
 * with the NON-winners that also touched the SAME extreme.
 *
 * We explicitly report winners and causal candidate conditions separately
 * from the holdout comparison. An outcome is never a trigger condition.
 * All signed bands are CUSTOM BTC INDEX bands, not Kalshi asks/probabilities.
 */
internal object BtcExtremeWinnerPatternMiner {
    internal data class Query(val band: Int, val atLeast80: Boolean = false) {
        val label: String get() = signed(band) + if (atLeast80) "+" else " exact"
        val direction: String get() = if (band > 0) "BUY DOWN" else "BUY UP"
        fun accepts(b: Int): Boolean = if (atLeast80) {
            if (band > 0) b >= 80 else b <= -80
        } else b == band
    }

    // 90 is primary; 80 EXACT and 80+ (including 90) are kept SEPARATE.
    internal val queries = listOf(
        Query(90), Query(-90), Query(80), Query(-80),
        Query(80, true), Query(-80, true)
    )

    internal data class Trace(
        val marketIndex: Int,
        val startMs: Long,
        val query: Query,
        val touchMinute: Int,
        val prior: List<Pair<Int, Int>>, // minute, signed band; NEVER touch or future
        val touchFeatures: BtcEarlyIndicatorResearchReport.Features?,
        val oppositeSettled: Boolean,
        val oppositeLater: Boolean
    ) {
        val left: Int get() = 15 - touchMinute
        val lastBand: Int? get() = prior.lastOrNull()?.second
        val sign: Int get() = if (query.band > 0) 1 else -1
        val fullFlip: Boolean get() = oppositeSettled && oppositeLater
    }

    private fun signed(n: Int): String = (if (n > 0) "+" else "") + n
    private fun fmt(value: Double): String =
        if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "N/A"
    private fun band(value: Double): Int? = BtcExhaustivePatternReport.band(value)

    /**
     * One EARLIEST touch per query per market. A case is labelled a
     * reversal if BTC settles opposite the extreme at market close,
     * even if an intermediate opposite signed candle was not observed.
     */
    internal fun traces(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>,
        query: Query
    ): List<Trace> = markets.mapIndexedNotNull { marketIndex, market ->
        val samples = market.minutes.asSequence()
            .filter { it.minute in 1..14 }
            .sortedBy { it.minute }
            .distinctBy { it.minute }
            .mapNotNull { p -> band(p.signedChartLevel)?.let { p.minute to it } }
            .toList()
        val touch = samples.firstOrNull { query.accepts(it.second) }
            ?: return@mapIndexedNotNull null
        val before = samples.filter { it.first < touch.first }
        val laterOpposite = samples.any { (minute, current) ->
            minute > touch.first && current * (if (query.band > 0) 1 else -1) <= -50
        }
        Trace(
            marketIndex, market.startMs, query, touch.first, before,
            features[market.startMs]?.get(touch.first),
            market.upWon != (query.band > 0), laterOpposite
        )
    }

    internal data class Rule(
        val family: String, val name: String,
        val matches: (Trace) -> Boolean
    )

    private val signedBands = listOf(-90, -80, -70, -60, -50, 50, 60, 70, 80, 90)

    /**
     * Candidate vocabulary defined up front. The order of two earlier bands
     * can be separated by ANY number of minutes, never restricted to 1/2m.
     * We choose/rank using older 700 only, then display newer 300 unchanged.
     */
    internal fun rules(): List<Rule> = buildList {
        add(Rule("TIME", "Extreme first seen with 11-14 min left") { it.left in 11..14 })
        add(Rule("TIME", "Extreme first seen with 7-10 min left") { it.left in 7..10 })
        add(Rule("TIME", "Extreme first seen with 3-6 min left") { it.left in 3..6 })
        add(Rule("TIME", "Extreme first seen with 1-2 min left") { it.left in 1..2 })

        for (magnitude in listOf(50, 60, 70, 80, 90)) {
            add(Rule("PRIOR ANY TIME", "Earlier opposite side >= " + magnitude) { trace ->
                trace.prior.any { (_, b) -> b * trace.sign <= -magnitude }
            })
            if (magnitude < 90) {
                add(Rule("PRIOR ANY TIME", "Earlier same side >= " + magnitude) { trace ->
                    trace.prior.any { (_, b) -> b * trace.sign >= magnitude }
                })
            }
        }
        add(Rule("PRIOR RECENCY", "Opposite side seen within prior 3 candles") { trace ->
            trace.prior.any { (minute, b) ->
                trace.touchMinute - minute <= 3 && b * trace.sign < 0
            }
        })
        add(Rule("PRIOR RECENCY", "Opposite side seen >=4 candles earlier") { trace ->
            trace.prior.any { (minute, b) ->
                trace.touchMinute - minute >= 4 && b * trace.sign < 0
            }
        })
        for (b in signedBands) {
            add(Rule("PREVIOUS CLOSE", "Immediately previous band was " + signed(b)) { trace ->
                trace.prior.lastOrNull()?.let { (minute, prev) ->
                    minute == trace.touchMinute - 1 && prev == b
                } == true
            })
            add(Rule("PRIOR ANY TIME", "Had band " + signed(b) + " ANY time before touch") { trace ->
                trace.prior.any { (_, prev) -> prev == b }
            })
        }
        add(Rule("PREVIOUS CLOSE", "Prior bar moved TOWARD the extreme") { trace ->
            val a = trace.prior.takeLast(2)
            a.size == 2 && a[0].second * trace.sign < a[1].second * trace.sign
        })
        add(Rule("PREVIOUS CLOSE", "Prior bar moved AWAY from the extreme") { trace ->
            val a = trace.prior.takeLast(2)
            a.size == 2 && a[0].second * trace.sign > a[1].second * trace.sign
        })
        add(Rule("PREVIOUS CLOSE", "Prior two bars were in SAME band") { trace ->
            val a = trace.prior.takeLast(2)
            a.size == 2 && a[0].second == a[1].second
        })

        add(Rule("INDICATOR AT TOUCH", "Volume at touch >=1.2x baseline") {
            (it.touchFeatures?.relVolume ?: Double.NaN) >= 1.2
        })
        add(Rule("INDICATOR AT TOUCH", "Volume at touch <0.8x baseline") {
            (it.touchFeatures?.relVolume ?: Double.NaN) < 0.8
        })
        add(Rule("INDICATOR AT TOUCH", "3m momentum AGAINST extreme at touch") {
            val m = it.touchFeatures?.momentum3 ?: Double.NaN
            m.isFinite() && m * it.sign < 0
        })
        add(Rule("INDICATOR AT TOUCH", "3m momentum WITH extreme at touch") {
            val m = it.touchFeatures?.momentum3 ?: Double.NaN
            m.isFinite() && m * it.sign > 0
        })
        add(Rule("INDICATOR AT TOUCH", "1m acceleration AGAINST extreme at touch") {
            val a = it.touchFeatures?.acceleration ?: Double.NaN
            a.isFinite() && a * it.sign < 0
        })
        add(Rule("INDICATOR AT TOUCH", "1m acceleration WITH extreme at touch") {
            val a = it.touchFeatures?.acceleration ?: Double.NaN
            a.isFinite() && a * it.sign > 0
        })
        add(Rule("TWO INDICATORS", "Volume >=1.2x AND momentum AGAINST extreme") {
            val f = it.touchFeatures
            f != null && f.relVolume >= 1.2 && f.momentum3 * it.sign < 0
        })
        add(Rule("TWO INDICATORS", "Momentum AND acceleration AGAINST extreme") {
            val f = it.touchFeatures
            f != null && f.momentum3 * it.sign < 0 && f.acceleration * it.sign < 0
        })
        add(Rule("TWO INDICATORS", "Previous opposite touch AND momentum AGAINST extreme") {
            val f = it.touchFeatures
            f != null && f.momentum3 * it.sign < 0 &&
                it.prior.any { (_, b) -> b * it.sign <= -50 }
        })

        // Ordered earlier pairs: X then Y at ANY later prior minute, then
        // the extreme; the gap is unrestricted, and both are before the touch.
        for (a in signedBands) for (b in signedBands) {
            add(Rule("ORDERED EARLIER PATH",
                "Earlier " + signed(a) + " THEN " + signed(b) + " (any gap)"
            ) { trace ->
                val seen = trace.prior
                seen.indices.any { i ->
                    seen[i].second == a &&
                        (i + 1 until seen.size).any { j -> seen[j].second == b }
                }
            })
        }
    }

    internal data class Count(val hits: Int, val reversed: Int, val meanLeft: Double) {
        val winPct: Double get() =
            if (hits == 0) Double.NaN else 100.0 * reversed / hits
        val lower95: Double get() {
            if (hits == 0) return Double.NaN
            val p = reversed.toDouble() / hits
            val z = 1.96
            val d = 1 + z * z / hits
            return 100.0 * (p + z * z / (2 * hits) -
                z * sqrt(p * (1 - p) / hits + z * z / (4.0 * hits * hits))) / d
        }
    }
    private fun counts(rows: List<Trace>) = Count(
        rows.size, rows.count { it.oppositeSettled },
        if (rows.isEmpty()) Double.NaN else rows.map { it.left }.average()
    )

    internal data class Scored(val rule: Rule, val old: Count, val fresh: Count)
    private fun scores(cases: List<Trace>, discovery: Int): List<Scored> {
        val old = cases.filter { it.marketIndex < discovery }
        val fresh = cases.filter { it.marketIndex >= discovery }
        return rules().map { rule ->
            Scored(rule, counts(old.filter(rule.matches)),
                counts(fresh.filter(rule.matches)))
        }
    }

    private fun StringBuilder.csvScored(
        q: Query, scored: Scored, oldBaseline: Count, newBaseline: Count,
        olderCount: Int, newerCount: Int
    ) {
        val o = scored.old
        val n = scored.fresh
        val liftOld = o.winPct - oldBaseline.winPct
        val liftNew = n.winPct - newBaseline.winPct
        val warning = if (n.hits < 15 || o.hits < 25) "THIN" else
            if (liftNew > 0) "HOLDOUT_UP" else "NO_HOLDOUT_LIFT"
        appendLine(
            q.label + "," + q.direction + "," + scored.rule.family + "," +
                scored.rule.name + "," +
                o.hits + "," + o.reversed + "," + fmt(o.winPct) + "," +
                fmt(o.lower95) + "," + fmt(liftOld) + "," +
                fmt(100.0 * o.hits / olderCount) + "," +
                n.hits + "," + n.reversed + "," + fmt(n.winPct) + "," +
                fmt(liftNew) + "," + fmt(100.0 * n.hits / newerCount) + "," +
                fmt(n.meanLeft) + "," + warning
        )
    }

    private fun describePrior(trace: Trace): String =
        if (trace.prior.isEmpty()) "none" else trace.prior.joinToString(" > ") {
            (minute, b) -> (15 - minute).toString() + "m:" + signed(b)
        }

    fun report(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, BtcEarlyIndicatorResearchReport.Features>>
    ): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.size < 2) return "Insufficient complete BTC markets for winner-first research."
        val discovery = (ordered.size * 7 / 10).coerceIn(1, ordered.lastIndex)
        val recent = ordered.size - discovery
        return buildString {
            appendLine("=== WINNER-FIRST 90 / 80 EXTREME PATTERN MINER ===")
            appendLine("Markets: " + ordered.size + "/1000 | discovery " + discovery +
                " oldest | comparison " + recent + " newest.")
            if (ordered.size != 1000) appendLine("INCOMPLETE SAMPLE: provisional.")
            appendLine("GOAL: first collect +90 -> settle DOWN and -90 -> settle UP WINNERS.")
            appendLine("Repeat for EXACT +80/-80 and 80+ inclusive; keep bins separate.")
            appendLine("Compare the winners to NON-WINNERS that ALSO touched that extreme.")
            appendLine("This IS NOT the unconditional reversal-rate report.")
            appendLine("First extreme touch can happen ANY completed minute; all candidate")
            appendLine("inputs (prior bands, volume, momentum, acceleration) must exist")
            appendLine("AT OR BEFORE THE FIRST TOUCH. No future candle leakage.")
            appendLine("Earlier band X THEN Y can occur ANY TIME before touch, any gap.")
            appendLine("Same-market cases, not independent trades; six groups OVERLAP.")
            appendLine("Candidate vocabulary fixed; rank by 95% Wilson lower bound")
            appendLine("in OLDEST only, with >=25 older touch cases / >=5 older reversals.")
            appendLine("Newest 300 never used for ranking. A row may fail validation.")
            appendLine("Market history previously explored: these 300 are NOT pristine.")
            appendLine("90/80 are CUSTOM BTC CHART INDEX bands, NOT Kalshi odds/asks.")
            appendLine("No historical Kalshi fills, spreads, fees or P&L.")
            appendLine("Live graph, four alert rules and 1400 scanner UNCHANGED.")
            appendLine()

            appendLine("=== WINNING REVERSAL CASE COUNTS ===")
            appendLine("extreme,buy,older_touch_cases,older_reversal_winners," +
                "newer_touch_cases,newer_reversal_winners,total_touch_cases," +
                "all_reversal_winners,all_full_flip_AND_settle_winners")
            for (q in queries) {
                val cases = traces(ordered, features, q)
                val old = counts(cases.filter { it.marketIndex < discovery })
                val fresh = counts(cases.filter { it.marketIndex >= discovery })
                appendLine(q.label + "," + q.direction + "," + old.hits + "," +
                    old.reversed + "," + fresh.hits + "," + fresh.reversed + "," +
                    cases.size + "," + cases.count { it.oppositeSettled } + "," +
                    cases.count { it.fullFlip })
            }
            appendLine()

            appendLine("=== PATTERNS FOUND IN WINNERS VS NON-WINNERS (DISCOVERY RANKING) ===")
            appendLine("Interpretation: a pattern found only among winners has ZERO")
            appendLine("predictive validity unless its same-time NON-winner denominator")
            appendLine("is counted. We report conditional settlement and coverage here.")
            appendLine("Scores are historical screening, NOT live probabilities.")
            appendLine("extreme,buy,family,past_pattern_or_touch_indicator," +
                "older_extreme_cases_matching,older_reversal_wins," +
                "older_win_pct,older_wilson_lower_95,older_lift_vs_base_pp," +
                "older_frequency_of_all_700_pct,newer_extreme_cases_matching," +
                "newer_reversal_wins,newer_win_pct,newer_lift_vs_base_pp," +
                "newer_frequency_of_all_300_pct,newer_mean_min_left,validation")
            for (q in queries) {
                val cases = traces(ordered, features, q)
                val oldBase = counts(cases.filter { it.marketIndex < discovery })
                val newBase = counts(cases.filter { it.marketIndex >= discovery })
                val qualifying = scores(cases, discovery).filter {
                    it.old.hits >= 25 && it.old.reversed >= 5
                }.sortedWith(compareByDescending<Scored> { it.old.lower95 }
                    .thenByDescending { it.old.hits }
                    .thenBy { it.rule.name })
                appendLine("# " + q.label + " base older: " + oldBase.reversed +
                    "/" + oldBase.hits + "; newer: " + newBase.reversed +
                    "/" + newBase.hits + "; rules meeting sample floor: " +
                    qualifying.size + "; TOP 20 ranked using OLDER only")
                if (qualifying.isEmpty()) appendLine("# No patterns meet 25 cases / 5 winners.")
                qualifying.take(20).forEach {
                    csvScored(q, it, oldBase, newBase, discovery, recent)
                }
            }
            appendLine()

            appendLine("=== WINNER-ONLY HISTORICAL CASE FILE (EVERY SUCCESSFUL REVERSAL) ===")
            appendLine("All below are selected AFTER observing settlement; NEVER use")
            appendLine("this selection as a live trigger. Use ranked controls above.")
            appendLine("extreme,side,market_start_utc,first_extreme_minutes_left," +
                "previous_band,all_prior_bands_with_minutes_left," +
                "touch_rel_volume,touch_3m_momentum_pct,touch_acceleration_pct," +
                "also_touched_opposite_later,settled")
            for (q in queries) {
                val cases = traces(ordered, features, q)
                    .filter { it.oppositeSettled }
                for (case in cases) {
                    val f = case.touchFeatures
                    appendLine(
                        q.label + "," + q.direction + "," +
                            Instant.ofEpochMilli(case.startMs).toString() + "," +
                            case.left + "," + (case.lastBand?.let(::signed) ?: "none") + "," +
                            describePrior(case) + "," +
                            fmt(f?.relVolume ?: Double.NaN) + "," +
                            fmt(f?.momentum3 ?: Double.NaN) + "," +
                            fmt(f?.acceleration ?: Double.NaN) + "," +
                            case.oppositeLater + "," +
                            (if (q.band > 0) "DOWN" else "UP")
                    )
                }
            }
            appendLine("=== END WINNER-FIRST RESEARCH; LIVE SIGNALS UNCHANGED ===")
        }
    }
}
