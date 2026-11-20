package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcFiveIndicatorEnsembleTest {
    private fun row(states: List<Int>, left: Int = 11): BtcEdgeTest.Row {
        require(states.size == 5)
        val signals = BtcFiveIndicatorEnsemble.NAMES.mapIndexedNotNull { index, name ->
            if (states[index] == 0) null else BtcEdgeTest.Signal(1, name, states[index])
        }
        return BtcEdgeTest.Row(60, left, signals)
    }

    private fun market(id: Int, up: Boolean, states: List<Int>, left: Int = 11) =
        BtcEdgeTest.Market(id.toLong(), up, listOf(row(states, left)))

    @Test fun allFiveVoteTogetherAndBothOverridePoliciesBeatChartWhenTrained() {
        val positive = List(5) { 1 }
        val negative = List(5) { -1 }
        val training = (0 until 700).map { i ->
            if (i < 500) market(i, true, positive)
            else market(i, false, negative)
        }
        // The chart learns UP on its constant 60 band. The ensemble learns
        // a separate DOWN prediction for the all-negative five-vote state.
        val holdout = (700 until 1000).map { i ->
            if (i < 800) market(i, true, positive)
            else market(i, false, negative)
        }
        val (score, byTime) = BtcFiveIndicatorEnsemble.score(training + holdout)
        assertEquals(300, score.observations)
        assertEquals(100, score.chartWins)
        assertEquals(300, score.voteWins)
        assertEquals(300, score.override4Wins)
        assertEquals(300, score.override5Wins)
        assertEquals(200, score.disagreementCases)
        assertEquals(200, score.disagreementVoteWins)
        assertEquals(200, score.overrides4)
        assertEquals(200, score.overrides4Correct)
        assertEquals(200, score.overrides5)
        assertEquals(200, score.overrides5Correct)
        assertEquals(300, byTime.getValue(11).observations)
        assertEquals(0, byTime.getValue(8).observations)
        val report = BtcFiveIndicatorEnsemble.report(training + holdout)
        assertTrue(report.contains("FIVE-INDICATOR MAJORITY: 300/300 correct (100.0%)"))
        assertTrue(report.contains("CHART ONLY on same eligible cases: 100/300 correct (33.3%)"))
    }

    @Test fun fourVotesMayOverrideWhileThreeVotesDoNotAndFiveRequiresUnanimity() {
        val positive = List(5) { 1 }
        val negative = List(5) { -1 }
        val training = (0 until 700).map { i ->
            market(i, i < 400, if (i < 400) positive else negative)
        }
        val holdout = (700 until 1000).map { i ->
            when {
                i < 800 -> market(i, true, listOf(1, 1, 1, -1, -1))  // 3 UP, same as chart
                i < 900 -> market(i, false, listOf(1, -1, -1, -1, -1)) // 4 DOWN
                else -> market(i, false, negative) // 5 DOWN
            }
        }
        val (s, _) = BtcFiveIndicatorEnsemble.score(training + holdout)
        assertEquals(300, s.observations)
        assertEquals(100, s.chartWins)
        assertEquals(300, s.voteWins)
        assertEquals(300, s.override4Wins)
        assertEquals(200, s.override5Wins)
        assertEquals(200, s.overrides4)
        assertEquals(100, s.overrides5)
        assertEquals(200, s.disagreementCases)
    }

    @Test fun missingSignalMustNotQuietlyCountAsFifthVote() {
        val pos = List(5) { 1 }
        val neg = List(5) { -1 }
        val training = (0 until 700).map { i ->
            market(i, i < 400, if (i < 400) pos else neg)
        }
        val holdout = (700 until 1000).map { i ->
            market(i, true, if (i < 800) listOf(1, 1, 1, 1, 0) else pos)
        }
        val (score, _) = BtcFiveIndicatorEnsemble.score(training + holdout)
        assertEquals(200, score.observations)
        assertEquals(200, score.chartWins)
        assertEquals(0, score.overrides4)
    }

    @Test fun futureOutcomesNeverRefitTrainingDirections() {
        val pos = List(5) { 1 }
        val training = (0 until 700).map { i -> market(i, true, pos) }
        val holdout = (700 until 1000).map { i -> market(i, false, pos) }
        val (score, _) = BtcFiveIndicatorEnsemble.score(training + holdout)
        assertEquals(300, score.observations)
        assertEquals(0, score.chartWins)
        assertEquals(0, score.voteWins)
        assertEquals(0, score.override4Wins)
        assertEquals(0, score.override5Wins)
    }
}
