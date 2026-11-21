package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcLiveNotificationTimingReportTest {
    private fun market(
        index: Long, upWon: Boolean, vararg samples: Pair<Int, Double>
    ) = BinanceTwentySevenPatternReport.Market(
        index * 900_000L, 100.0,
        if (upWon) 101.0 else 99.0,
        samples.map {
            BinanceTwentySevenPatternReport.Observation(it.first, it.second)
        }
    )

    @Test fun recordsExactMainGraphPatternHitAndSettlementWinAtCorrectMinute() {
        val cases = listOf(
            market(1, false, 1 to -55.0, 3 to -65.0),
            market(2, true, 1 to -55.0, 3 to -65.0),
            market(3, true, 1 to 55.0, 2 to 65.0),
            market(4, true, 2 to 55.0, 3 to 65.0),
            market(5, true, 4 to 55.0, 5 to 65.0)
        )
        val result = BtcLiveNotificationTimingReport.analyze(cases)
        val down = result.row(-50, -60, 2, 12)
        assertEquals(14, down.firstLeft)
        assertEquals(2, down.matched.all.hits)
        assertEquals(1, down.matched.all.wins)
        assertEquals(50.0, down.matched.all.winPct, 0.0001)
        assertEquals(2, down.eligibleMatch.all.hits)
        assertEquals(2, down.notified.all.hits)
        assertEquals(1, down.notified.all.wins)

        // The EXACT live gate rejects the 14 -> 13 UP path.
        val rejected = result.row(50, 60, 1, 13)
        assertEquals(1, rejected.matched.all.hits)
        assertEquals(1, rejected.matched.all.wins)
        assertEquals(0, rejected.eligibleMatch.all.hits)
        assertEquals(0, rejected.notified.all.hits)

        // 11 -> 10 qualifies and is an actual simulated first notification.
        val accepted = result.row(50, 60, 1, 10)
        assertEquals(1, accepted.matched.all.hits)
        assertEquals(1, accepted.notified.all.hits)
        assertEquals(1, accepted.notified.all.wins)
        assertEquals(3, result.uniqueNotified.all.hits)
        assertEquals(2, result.uniqueNotified.all.wins)
    }

    @Test fun onlyFirstEligibleNotificationIsSentAcrossTheFourRules() {
        val case = market(1, false,
            1 to -55.0, 3 to -65.0, 4 to 55.0, 5 to 65.0)
        val report = BtcLiveNotificationTimingReport.analyze(listOf(case))
        assertEquals(1, report.uniqueNotified.all.hits)
        assertEquals(1, report.row(-50, -60, 2, 12).notified.all.hits)
        val late = report.row(50, 60, 1, 10)
        assertEquals(1, late.matched.all.hits)
        assertEquals(1, late.eligibleMatch.all.hits)
        assertEquals(0, late.notified.all.hits)
    }

    @Test fun exactSignedBandsNotThresholdCrossingsAndNoImpossibleStart() {
        val case = market(1, false, 1 to -55.0, 3 to -71.0)
        val a = BtcLiveNotificationTimingReport.analyze(listOf(case))
        assertEquals(0, a.row(-50, -60, 2, 12).matched.all.hits)
        assertNull(a.row(-50, -60, 1, 14).firstLeft)
        assertEquals(0, a.row(-50, -60, 1, 14).matched.all.hits)
        assertEquals(0, a.uniqueNotified.all.hits)
    }

    @Test fun graphCsvIncludesHitRateAndWinRateForEveryFinishMinuteAndBothSamples() {
        val cases = listOf(
            market(1, false, 1 to -55.0, 3 to -65.0),
            market(2, true, 1 to -55.0, 3 to -65.0),
            market(3, true, 4 to 55.0, 5 to 65.0)
        )
        val text = BtcLiveNotificationTimingReport.report(cases)
        assertTrue(text.contains("HIT RATE AT TIME"))
        assertTrue(text.contains("WIN RATE AT TIME"))
        assertTrue(text.contains("hit_rate_pct_of_all_markets"))
        assertTrue(text.contains("win_rate_pct"))
        assertTrue(text.contains("first_notification_hit_rate_pct"))
        assertTrue(text.contains("EXACT-TIME 75% WIN-RATE MINIMUM"))
        assertTrue(text.contains("NO CHART CHANGES"))
        assertEquals(4 * 14, text.lineSequence()
            .count { it.startsWith("-50,") || it.startsWith("+50,") })
        assertTrue(text.contains("-50,-60,2,DOWN,14,12,2,66.7,1,50.0"))
    }
}
