package com.decisionoverlay.checker

import java.util.Locale

/**
 * Preselected five-indicator ensemble. It tests one joint decision per
 * market/checkpoint, not five separate "wins". All five states are taken
 * from the same fully closed 1-minute candle at that decision moment.
 *
 * Older 700 of 1,000 markets fit each (indicator, state, minute-left)
 * direction. Newest 300 compare five-vote majority against the chart and
 * two predefined, conservative chart-override policies. No holdout
 * outcome is used to fit a vote or a chart prediction.
 */
internal object BtcFiveIndicatorEnsemble {
    internal val NAMES = listOf(
        "CCI14", "Bollinger20", "MACD5/13/4", "Williams%R14", "ROC10"
    )
    private val CHECKPOINTS = listOf(11, 8, 5, 2)
    private const val MIN_TRAIN_SAMPLES = 40

    private data class TrainingKey(val name: String, val state: Int, val left: Int)
    private data class ChartKey(val band: Int, val left: Int)
    private class Counts {
        var hits = 0
        var up = 0
        fun add(isUp: Boolean) {
            hits++
            if (isUp) up++
        }
        fun direction(minimum: Int): Boolean? {
            if (hits < minimum || up * 2 == hits) return null
            return up * 2 > hits
        }
    }

    internal data class Score(
        var observations: Int = 0,
        var chartWins: Int = 0,
        var voteWins: Int = 0,
        var override4Wins: Int = 0,
        var override5Wins: Int = 0,
        var disagreementCases: Int = 0,
        var disagreementVoteWins: Int = 0,
        var overrides4: Int = 0,
        var overrides4Correct: Int = 0,
        var overrides5: Int = 0,
        var overrides5Correct: Int = 0,
        var strong4Cases: Int = 0,
        var strong4Wins: Int = 0,
        var unanimousCases: Int = 0,
        var unanimousWins: Int = 0
    ) {
        fun record(actualUp: Boolean, chartUp: Boolean, votesUp: Int) {
            require(votesUp in 0..5)
            observations++
            val voteUp = votesUp >= 3
            val voteStrength = maxOf(votesUp, 5 - votesUp)
            if (chartUp == actualUp) chartWins++
            if (voteUp == actualUp) voteWins++
            if (voteUp != chartUp) {
                disagreementCases++
                if (voteUp == actualUp) disagreementVoteWins++
            }
            if (voteStrength >= 4) {
                strong4Cases++
                if (voteUp == actualUp) strong4Wins++
            }
            if (voteStrength == 5) {
                unanimousCases++
                if (voteUp == actualUp) unanimousWins++
            }
            val changed4 = voteStrength >= 4 && voteUp != chartUp
            val changed5 = voteStrength == 5 && voteUp != chartUp
            if (changed4) {
                overrides4++
                if (voteUp == actualUp) overrides4Correct++
            }
            if (changed5) {
                overrides5++
                if (voteUp == actualUp) overrides5Correct++
            }
            if ((if (changed4) voteUp else chartUp) == actualUp) override4Wins++
            if ((if (changed5) voteUp else chartUp) == actualUp) override5Wins++
        }
    }

    private fun percent(correct: Int, total: Int): String =
        if (total == 0) "n/a" else String.format(Locale.US, "%.1f%%", 100.0 * correct / total)

    /**
     * Votes and baselines are learned independently from the 700 oldest
     * markets. The 300 holdout markets are evaluated in chronological order.
     * Missing, zero, tied or poorly sampled states are skipped rather than
     * secretly being filled with the final outcome.
     */
    internal fun score(markets: List<BtcEdgeTest.Market>): Pair<Score, Map<Int, Score>> {
        require(markets.size == 1000 && markets.map { it.at }.distinct().size == 1000)
        val sorted = markets.sortedBy { it.at }
        val chartTraining = mutableMapOf<ChartKey, Counts>()
        val signalTraining = mutableMapOf<TrainingKey, Counts>()
        for (market in sorted.take(700)) {
            for (row in market.rows) {
                if (row.left !in CHECKPOINTS) continue
                chartTraining.getOrPut(ChartKey(row.band, row.left)) { Counts() }
                    .add(market.up)
                for (name in NAMES) {
                    val signal = row.signals.singleOrNull {
                        it.timeframe == 1 && it.indicator == name && it.state != 0
                    } ?: continue
                    signalTraining.getOrPut(TrainingKey(name, signal.state, row.left)) {
                        Counts()
                    }.add(market.up)
                }
            }
        }
        val total = Score()
        val byCheckpoint = CHECKPOINTS.associateWith { Score() }
        for (market in sorted.drop(700)) {
            for (row in market.rows) {
                if (row.left !in CHECKPOINTS) continue
                val chartDirection = chartTraining[ChartKey(row.band, row.left)]
                    ?.direction(1) ?: continue
                val predictions = NAMES.map { name ->
                    val s = row.signals.singleOrNull {
                        it.timeframe == 1 && it.indicator == name && it.state != 0
                    } ?: return@map null
                    signalTraining[TrainingKey(name, s.state, row.left)]
                        ?.direction(MIN_TRAIN_SAMPLES)
                }
                if (predictions.any { it == null }) continue
                val votesUp = predictions.count { it == true }
                total.record(market.up, chartDirection, votesUp)
                byCheckpoint.getValue(row.left).record(market.up, chartDirection, votesUp)
            }
        }
        return total to byCheckpoint
    }

    fun report(markets: List<BtcEdgeTest.Market>): String {
        val (total, byCheckpoint) = score(markets)
        fun reportLine(label: String, s: Score, correct: Int): String =
            "$label: $correct/${s.observations} correct (${percent(correct, s.observations)})"
        return buildString {
            appendLine()
            appendLine("=== FIVE INDICATORS TOGETHER VS CHART (1m CANDLES) ===")
            appendLine("Five inputs at the SAME decision time: " + NAMES.joinToString(" + "))
            appendLine("For every 11/8/5/2-minutes-left checkpoint, all five vote once.")
            appendLine("Each positive/negative vote maps to historical UP/DOWN using ONLY oldest 700 training markets.")
            appendLine("Missing/zero/tied or fewer than 40 training matches for any input = no ensemble decision.")
            appendLine("Newest 300 markets are the held-out test. One checkpoint is one decision; each market can contribute four correlated decisions.")
            appendLine("Unweighted majority vote: >=3 of 5; chart + votes (4/5): override chart ONLY when >=4 agree and disagree;")
            appendLine("chart + votes (5/5): override chart ONLY when all five agree and disagree.")
            appendLine("Coverage: ${total.observations}/1200 possible holdout decisions; missing = ${1200 - total.observations}.")
            appendLine(reportLine("CHART ONLY on same eligible cases", total, total.chartWins))
            appendLine(reportLine("FIVE-INDICATOR MAJORITY", total, total.voteWins))
            appendLine(reportLine("CHART + OVERRIDE ON 4/5", total, total.override4Wins))
            appendLine(reportLine("CHART + OVERRIDE ON 5/5", total, total.override5Wins))
            appendLine("Majority disagreed with chart ${total.disagreementCases} times; right on ${total.disagreementVoteWins}/${total.disagreementCases} disagreements.")
            appendLine("4/5 overrides: ${total.overrides4}; correct ${total.overrides4Correct}/${total.overrides4}; strong-vote accuracy ${percent(total.strong4Wins, total.strong4Cases)} on ${total.strong4Cases} cases.")
            appendLine("5/5 overrides: ${total.overrides5}; correct ${total.overrides5Correct}/${total.overrides5}; unanimous-vote accuracy ${percent(total.unanimousWins, total.unanimousCases)} on ${total.unanimousCases} cases.")
            appendLine()
            appendLine("Time left,eligible,chart_correct,chart_pct,five_vote_correct,five_vote_pct,chart_plus_4of5_correct,chart_plus_4of5_pct,chart_plus_5of5_correct,chart_plus_5of5_pct,4of5_overrides,5of5_overrides")
            for (left in CHECKPOINTS) {
                val s = byCheckpoint.getValue(left)
                appendLine(listOf(left, s.observations, s.chartWins, percent(s.chartWins, s.observations),
                    s.voteWins, percent(s.voteWins, s.observations),
                    s.override4Wins, percent(s.override4Wins, s.observations),
                    s.override5Wins, percent(s.override5Wins, s.observations),
                    s.overrides4, s.overrides5).joinToString(","))
            }
            appendLine()
            appendLine("These five were chosen AFTER inspecting an earlier holdout: this replay is exploratory, not a new independent out-of-sample test.")
            appendLine("No fees, fills, price odds, trading return, or guaranteed edge measured. No orders placed.")
        }
    }
}
