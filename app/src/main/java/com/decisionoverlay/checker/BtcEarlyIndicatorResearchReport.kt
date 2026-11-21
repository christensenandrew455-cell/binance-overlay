package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.sqrt

/** Research only. No Kalshi quotes, fills, live notifications or chart changes. */
internal object BtcEarlyIndicatorResearchReport {
    private const val M = 60_000L
    internal data class Features(val relVolume: Double, val momentum3: Double, val acceleration: Double)

    // Every candle referenced was completed by the observation minute.
    internal fun featuresAt(start: Long, minute: Int, candles: Map<Long, BtcAcmMath.Candle>): Features? {
        if (minute !in 1..14) return null
        val at = start + (minute - 1L) * M
        fun bar(back: Int) = candles[at - back * M]?.takeIf {
            it.valid() && it.volume.isFinite() && it.volume >= 0.0
        }
        val bars = (0..14).map { bar(it) ?: return null }
        val earlier = bars.drop(3).sumOf { it.volume }
        if (earlier <= 0.0) return null
        val one = 100.0 * (bars[0].close / bars[1].close - 1.0)
        val three = 100.0 * (bars[0].close / bars[3].close - 1.0)
        val f = Features(
            bars.take(3).sumOf { it.volume } / (earlier / 4.0),
            three, one - (three - one) / 2.0
        )
        return f.takeIf { listOf(it.relVolume, it.momentum3, it.acceleration).all(Double::isFinite) }
    }

    internal enum class Gate(val label: String) {
        NONE("Unfiltered"), VOLUME("Volume >= 1.2x"),
        MOMENTUM("3m momentum with side"), ACCEL("1m acceleration with side"),
        VOL_MOM("Volume + momentum"), ALL("Volume + momentum + acceleration"),
        COUNTER("3m momentum against side");

        fun accepts(f: Features?, buyUp: Boolean): Boolean {
            if (this == NONE) return true
            if (f == null) return false
            val sign = if (buyUp) 1.0 else -1.0
            val v = f.relVolume >= 1.2
            val m = sign * f.momentum3 > 0
            val a = sign * f.acceleration > 0
            return when (this) {
                NONE -> true
                VOLUME -> v
                MOMENTUM -> m
                ACCEL -> a
                VOL_MOM -> v && m
                ALL -> v && m && a
                COUNTER -> sign * f.momentum3 < 0
            }
        }
    }

    internal data class Candidate(
        val from: Int, val to: Int, val gap: Int, val buyUp: Boolean, val existing: Boolean
    ) {
        val label get() = "${signed(from)} to ${signed(to)} in ${gap}m"
        val side get() = if (buyUp) "UP" else "DOWN"
    }
    private fun signed(band: Int) = (if (band > 0) "+" else "") + band
    // Predefined; do not mine paths on the newer 300 markets.
    internal val candidates = buildList {
        for (r in BtcEightPatternReport.rules)
            add(Candidate(r.path.from, r.path.to, r.path.gap, r.buyUp, true))
        for (sign in listOf(-1, 1))
            for ((from, to) in listOf(70 to 60, 60 to 50, 70 to 50))
                for (gap in 1..2)
                    add(Candidate(sign * from, sign * to, gap, sign > 0, false))
    }
    internal data class Outcome(
        val market: Int, val minutesLeft: Int, val won: Boolean, val reached: Boolean = false
    )
    internal data class Stats(
        val count: Int, val wins: Int, val avgLeft: Double, val reaches: Int
    ) {
        val winPct get() = if (count == 0) Double.NaN else 100.0 * wins / count
        val reachPct get() = if (count == 0) Double.NaN else 100.0 * reaches / count
        val lower get(): Double {
            if (count == 0) return 0.0
            val p = wins.toDouble() / count
            val z = 1.96
            val d = 1 + z * z / count
            return 100.0 * (p + z * z / (2 * count) -
                z * sqrt(p * (1 - p) / count + z * z / (4.0 * count * count))) / d
        }
    }
    private fun stats(outcomes: List<Outcome>): Stats =
        Stats(outcomes.size, outcomes.count { it.won },
            if (outcomes.isEmpty()) Double.NaN else outcomes.map { it.minutesLeft }.average(),
            outcomes.count { it.reached })
    private data class Split(val old: Stats, val new: Stats)
    private fun split(outcomes: List<Outcome>, size: Int) = Split(
        stats(outcomes.filter { it.market < size }), stats(outcomes.filter { it.market >= size })
    )
    private fun band(value: Double?) = BtcExhaustivePatternReport.band(value ?: Double.NaN)

    // Earliest eligible completed path per market; alternative filters may trigger later.
    internal fun pathOutcomes(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, Features>>, candidate: Candidate, gate: Gate
    ): List<Outcome> = markets.mapIndexedNotNull { index, market ->
        val byMinute = market.minutes.associateBy { it.minute }
        val minute = ((candidate.gap + 1)..14).firstOrNull { t ->
            band(byMinute[t - candidate.gap]?.signedChartLevel) == candidate.from &&
                band(byMinute[t]?.signedChartLevel) == candidate.to &&
                gate.accepts(features[market.startMs]?.get(t), candidate.buyUp)
        } ?: return@mapIndexedNotNull null
        Outcome(index, 15 - minute, market.upWon == candidate.buyUp)
    }

    internal data class Lead(val from: Int, val to: Int, val up: Boolean) {
        val label get() = "${signed(from)} predicts ${signed(to)} within 2m"
    }
    internal val leads = listOf(Lead(-70, -60, false), Lead(-60, -50, false),
        Lead(+70, +60, true), Lead(+60, +50, true))

    // Future candles label a prediction, but never contribute to the indicator gate.
    internal fun leadOutcomes(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, Features>>, lead: Lead, gate: Gate
    ): List<Outcome> = markets.mapIndexedNotNull { index, market ->
        val byMinute = market.minutes.associateBy { it.minute }
        val t = (1..12).firstOrNull { minute ->
            band(byMinute[minute]?.signedChartLevel) == lead.from &&
                gate.accepts(features[market.startMs]?.get(minute), lead.up)
        } ?: return@mapIndexedNotNull null
        val reached = (t + 1..t + 2).any { minute ->
            band(byMinute[minute]?.signedChartLevel) == lead.to
        }
        Outcome(index, 15 - t, market.upWon == lead.up, reached)
    }

    private fun fmt(value: Double) =
        if (value.isFinite()) String.format(Locale.US, "%.1f", value) else "N/A"
    private fun columns(s: Split) =
        "${s.old.count},${s.old.wins},${fmt(s.old.winPct)},${fmt(s.old.lower)}," +
        "${fmt(s.old.avgLeft)},${s.new.count},${s.new.wins}," +
        "${fmt(s.new.winPct)},${fmt(s.new.avgLeft)}"

    fun report(
        markets: List<BinanceTwentySevenPatternReport.Market>,
        features: Map<Long, Map<Int, Features>>
    ): String {
        val ordered = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.isEmpty()) return "No complete historical markets."
        val older = (ordered.size * 7 / 10).coerceAtLeast(1)
        val newer = ordered.size - older
        return buildString {
            appendLine("=== EARLY BTC VOLUME + MOMENTUM EXPERIMENT (RESEARCH ONLY) ===")
            appendLine("Completed BTC markets: ${ordered.size}/1000; oldest $older select; newest $newer hold out.")
            if (ordered.size != 1000) appendLine("INCOMPLETE: results provisional.")
            appendLine("Same BTC candles and signed custom INDEX as four-pattern backtest, THIS run.")
            appendLine("Indicators use CLOSED 1m candles only. Volume = last 3m vs prior 12m baseline.")
            appendLine("Momentum = 3m BTC return; acceleration = latest 1m return vs preceding 2m average (approx).")
            appendLine("Thresholds and candidate paths fixed in code; no newer-market filter selection.")
            appendLine("IMPORTANT: These historical markets were previously examined; the newer 300")
            appendLine("are a within-run check, NOT pristine independent out-of-sample validation.")
            appendLine("New future markets and real Kalshi quote/fee data are needed before trading.")
            appendLine("Require >=20 older hits and >=30% of baseline older coverage to SELECT a gate.")
            appendLine("One earliest qualifying match per market/candidate/gate. Candidate hits overlap.")
            appendLine("Earlier-band signals are scored on the next TWO candle closes as well as final settlement.")
            appendLine("NO Kalshi price, probability, fees, order fills, profit, live alert or graph changes.")
            appendLine()
            appendLine("=== HOLDOUT COMPARISON (gate chosen only by oldest 70%) ===")
            for (candidate in candidates) {
                val rows = Gate.entries.associateWith { gate ->
                    split(pathOutcomes(ordered, features, candidate, gate), older)
                }
                val baseline = rows.getValue(Gate.NONE)
                val best = rows.filter { (_, s) ->
                    s.old.count >= 20 && s.old.count >= baseline.old.count * 0.3
                }.maxWithOrNull(compareBy<Map.Entry<Gate, Split>> { it.value.old.lower }
                    .thenBy { it.value.old.count })?.key ?: Gate.NONE
                val selected = rows.getValue(best)
                val verdict = when {
                    best == Gate.NONE || baseline.new.count < 10 || selected.new.count < 10 ->
                        "INSUFFICIENT/UNFILTERED"
                    selected.new.winPct > baseline.new.winPct -> "HIGHER HOLDOUT WIN RATE"
                    else -> "NO HOLDOUT IMPROVEMENT"
                }
                appendLine("${candidate.label} BUY ${candidate.side}" +
                    (if (candidate.existing) " [EXISTING]" else " [EARLIER]") +
                    " | selected: ${best.label}; oldest ${selected.old.count} hits," +
                    " ${fmt(selected.old.winPct)}% win;" +
                    " newest baseline ${baseline.new.count} hits/${fmt(baseline.new.winPct)}%" +
                    " vs selected ${selected.new.count} hits/${fmt(selected.new.winPct)}%" +
                    " | mean min left ${fmt(baseline.new.avgLeft)} -> " +
                    "${fmt(selected.new.avgLeft)} | $verdict")
            }
            appendLine()
            appendLine("=== ALL PATTERN + INDICATOR COMBINATIONS (CSV) ===")
            appendLine("pattern,side,existing,filter,older_hits,older_wins,older_win_pct," +
                "older_wilson_lower_pct,older_mean_minutes_left,newer_hits,newer_wins," +
                "newer_win_pct,newer_mean_minutes_left")
            for (candidate in candidates) for (gate in Gate.entries) {
                val s = split(pathOutcomes(ordered, features, candidate, gate), older)
                appendLine("${candidate.label},${candidate.side},${candidate.existing}," +
                    "${gate.label},${columns(s)}")
            }
            appendLine()
            appendLine("=== BEFORE THE MOVEMENT: TWO-MINUTE BAND PREDICTION (CSV) ===")
            appendLine("starting_band,next_band,side,filter,older_hits,older_settle_pct," +
                "older_transition_pct,newer_hits,newer_settle_pct,newer_transition_pct," +
                "newer_mean_minutes_left")
            for (lead in leads) for (gate in Gate.entries) {
                val s = split(leadOutcomes(ordered, features, lead, gate), older)
                appendLine("${signed(lead.from)},${signed(lead.to)}," +
                    "${if (lead.up) "UP" else "DOWN"},${gate.label},${s.old.count}," +
                    "${fmt(s.old.winPct)},${fmt(s.old.reachPct)},${s.new.count}," +
                    "${fmt(s.new.winPct)},${fmt(s.new.reachPct)},${fmt(s.new.avgLeft)}")
            }
            appendLine("=== END EARLY RESEARCH; NO LIVE CHANGES ===")
        }
    }
}
