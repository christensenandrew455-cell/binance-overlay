package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcExtremeWinnerPatternMinerTest {
    private val miner = BtcExtremeWinnerPatternMiner

    private fun market(id: Long, settledUp: Boolean, vararg points: Pair<Int, Double>) =
        BinanceTwentySevenPatternReport.Market(
            id * 900_000L, 100.0, if (settledUp) 101.0 else 99.0,
            points.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) }
        )

    @Test fun collectWinnersThenCompareAgainstControlsTouchingSameNinety() {
        val winner = market(1, false, 1 to -65.0, 3 to 55.0,
            6 to 75.0, 9 to 94.0, 12 to -56.0)
        val nonwinner = market(2, true, 1 to -65.0, 3 to 55.0,
            6 to 75.0, 9 to 94.0)
        val missing = market(3, false, 1 to -65.0, 3 to 54.0)
        val q = miner.queries.first { it.band == 90 && !it.atLeast80 }
        val rows = miner.traces(listOf(winner, nonwinner, missing), emptyMap(), q)
        assertEquals(2, rows.size)
        assertEquals(1, rows.count { it.oppositeSettled })
        assertEquals(1, rows.count { it.fullFlip })
        assertEquals(6, rows[0].left)
        assertTrue(rows[0].prior.all { it.first < 9 })
        assertEquals(70, rows[0].lastBand)
        assertFalse(rows[1].oppositeSettled)

        val priorOpposite = miner.rules().first {
            it.name == "Earlier opposite side >= 60"
        }
        // Same earlier signal occurs in both winners and nonwinners:
        // this is NOT a 100% predictive pattern.
        assertTrue(priorOpposite.matches(rows[0]))
        assertTrue(priorOpposite.matches(rows[1]))
    }

    @Test fun orderedEarlierPathsAllowArbitraryMinuteGapsButNotFuture() {
        val w = market(1, false, 1 to -64.0, 4 to 75.0,
            7 to 55.0, 10 to 94.0, 14 to -65.0)
        val q = miner.queries.first { it.band == 90 && !it.atLeast80 }
        val trace = miner.traces(listOf(w), emptyMap(), q).single()
        val before = miner.rules().first {
            it.name == "Earlier -60 THEN +70 (any gap)"
        }
        val after = miner.rules().first {
            it.name == "Earlier +90 THEN -60 (any gap)"
        }
        assertTrue(before.matches(trace))
        assertFalse(after.matches(trace))
        assertEquals(5, trace.left)
        assertEquals(3, trace.prior.size)
        assertTrue(trace.oppositeSettled)
        assertTrue(trace.oppositeLater)
    }

    @Test fun exactEightyAndEightyPlusNinetyAreDifferentGroups() {
        val low = market(1, false, 1 to 84.0, 5 to -55.0)
        val high = market(2, false, 1 to 94.0, 5 to -55.0)
        val list = listOf(low, high)
        val exact80 = miner.queries.first { it.band == 80 && !it.atLeast80 }
        val inclusive80 = miner.queries.first { it.band == 80 && it.atLeast80 }
        val exact90 = miner.queries.first { it.band == 90 }
        assertEquals(1, miner.traces(list, emptyMap(), exact80).size)
        assertEquals(2, miner.traces(list, emptyMap(), inclusive80).size)
        assertEquals(1, miner.traces(list, emptyMap(), exact90).size)
    }

    @Test fun indicatorsOnlyUseFeaturesProvidedAtExtremeCandle() {
        val win = market(1, false, 1 to 55.0, 5 to 94.0, 11 to -55.0)
        val q = miner.queries.first { it.band == 90 }
        val features = mapOf(win.startMs to mapOf(
            5 to BtcEarlyIndicatorResearchReport.Features(1.5, -0.2, -0.1),
            11 to BtcEarlyIndicatorResearchReport.Features(9.9, +4.0, +3.0)
        ))
        val row = miner.traces(listOf(win), features, q).single()
        assertEquals(1.5, row.touchFeatures!!.relVolume, 0.001)
        assertTrue(miner.rules().first {
            it.name == "3m momentum AGAINST extreme at touch"
        }.matches(row))
    }

    @Test fun reportShowsCaseFileAndOlderOnlyRankingWithoutPretendingItsLiveOdds() {
        val cases = listOf(
            market(1, false, 1 to -65.0, 4 to 94.0, 8 to -55.0),
            market(2, true, 1 to 65.0, 5 to -95.0, 11 to +55.0),
            market(3, true, 2 to 94.0, 9 to 75.0)
        )
        val report = miner.report(cases, emptyMap())
        assertTrue(report.contains("WINNER-FIRST 90 / 80"))
        assertTrue(report.contains("WINNING REVERSAL CASE COUNTS"))
        assertTrue(report.contains("PATTERNS FOUND IN WINNERS VS NON-WINNERS"))
        assertTrue(report.contains("WINNER-ONLY HISTORICAL CASE FILE"))
        assertTrue(report.contains("CUSTOM BTC CHART INDEX"))
        assertTrue(report.contains("LIVE SIGNALS UNCHANGED"))
        assertTrue(report.contains("BEFORE THE FIRST TOUCH"))
    }
}
