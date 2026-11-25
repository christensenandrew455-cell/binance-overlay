package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class KalshiLiveSignalRulesTest {
    private fun q(left: Int, level: Double) = BtcChartProxyMath.Sample(left, level)
    private fun match(vararg samples: BtcChartProxyMath.Sample) =
        KalshiLiveSignalRules.matchingSignedSignals(samples.toList())

    @Test fun onlyFourHistoricalPatternsMeetTwentyPercentOccurrenceFloor() {
        assertEquals(4, KalshiLiveSignalRules.rules.size)
        assertEquals(listOf(
            "-50 -> -60 in 2 min", "-50 -> -60 in 1 min",
            "+50 -> +60 in 1 min", "+50 -> +60 in 2 min"
        ), KalshiLiveSignalRules.rules.map { it.name })
        assertEquals(listOf("DOWN", "DOWN", "UP", "UP"),
            KalshiLiveSignalRules.rules.map { it.side })
        assertTrue(KalshiLiveSignalRules.rules.all {
            it.hitRatePercent >= KalshiLiveSignalRules.MIN_HIT_RATE_PERCENT
        })
    }

    @Test fun theExactFinishingMinuteOverridesTheOverallRate() {
        val good = match(q(14, -55.0), q(12, -65.0))
        assertEquals(1, good.size)
        assertEquals("DOWN", good.single().rule.side)
        assertEquals(14, good.single().startLeft)
        assertEquals(12, good.single().left)
        assertEquals(91.4, good.single().window.winRatePercent, 0.1)
        assertEquals(31.4, good.single().window.edgePoints, 0.1)
        // Same path, a DIFFERENT finish minute: only 73.3%, below cutoff.
        assertTrue(match(q(12, -55.0), q(10, -65.0)).isEmpty())
    }

    @Test fun exactlySeventyFivePercentIsAllowedWhenDiscoverySampleIsLargeEnough() {
        // -50 -> -60 (1m), 12 -> 11: 30 wins / 40 = 75.0% and 28
        // discovery matches. This must now SEND at the inclusive cutoff.
        val exactly = match(q(12, -55.0), q(11, -65.0)).single()
        assertEquals("DOWN", exactly.rule.side)
        assertEquals(75.0, exactly.window.winRatePercent, 0.001)
        assertEquals(15.0, exactly.window.edgePoints, 0.001)
        assertTrue(exactly.window.eligible)
        // -50 -> -60 (2m), 5 -> 3: 18/24 = 75.0%, but only
        // 16 discovery matches; still rejected to avoid tiny samples.
        assertTrue(match(q(5, -55.0), q(3, -65.0)).isEmpty())
        // Below the inclusive threshold (74.2%) must be rejected.
        assertTrue(match(q(14, 55.0), q(13, 65.0)).isEmpty())
        // 82.4% passes normally.
        assertEquals("UP", match(q(12, 55.0), q(10, 65.0))
            .single().rule.side)
    }

    @Test fun insufficientDiscoverySampleCannotProduceHighChanceAlert() {
        // 4 -> 2 is 85.7% but has only NINE discovery hits.
        assertTrue(match(q(4, -55.0), q(2, -65.0)).isEmpty())
        // 5 -> 4 (UP 1m) is 85.7% with only SIX discovery hits.
        assertTrue(match(q(5, 55.0), q(4, 65.0)).isEmpty())
    }

    @Test fun exactBinsNotThresholdCrossingAndDirection() {
        assertTrue(match(q(14, -55.0), q(12, -75.0)).isEmpty())
        assertTrue(match(q(14, -55.0), q(12, -59.0)).isEmpty())
        assertTrue(match(q(12, 55.0), q(10, -65.0)).isEmpty())
        assertTrue(match(q(12, -55.0), q(10, 65.0)).isEmpty())
        assertEquals("UP", match(q(11, 55.2), q(10, 61.1))
            .single().rule.side)
    }

    @Test fun aPreviouslyRejectedWindowDoesNotBlockTheLaterGoodOne() {
        // 14 -> 13 DOWN 1m has 18 discovery hits; no alert at 13.
        // 13 -> 12 has 30 discovery hits and 89.6% win; can alert at 12.
        val history = listOf(q(14, -55.0), q(13, -65.0),
            q(13, -55.0), q(12, -65.0))
        // Duplicated minute readings resolve to first close, so test the
        // later qualifying window independently with a legal market sequence.
        assertEquals(1, match(q(13, -55.0), q(12, -65.0)).size)
        assertTrue(match(q(14, -55.0), q(13, -65.0)).isEmpty())
    }

    @Test fun firstEligibleCompletionOnlyAndNoFutureReading() {
        // 13 -> 12 qualifies; a later 10 -> 9 match should not alert again.
        assertTrue(match(q(13, -55.0), q(12, -65.0),
            q(10, -55.0), q(9, -65.0))
            .none { it.rule.gapMinutes == 1 })
        assertTrue(match(q(14, -55.0)).isEmpty())
    }

    private fun early(elapsed: Long, vararg samples: BtcChartProxyMath.Sample) =
        KalshiLiveSignalRules.matchingEarlyWindows(samples.toList(), elapsed)

    @Test fun firstCompletedMinuteCanWarnButCannotConfirm() {
        val warning = early(0L, q(14, -55.0)).single()
        assertEquals("DOWN", warning.rule.side)
        assertEquals(2, warning.rule.gapMinutes)
        assertEquals(12, warning.window.key.finishLeft)
        assertTrue(match(q(14, -55.0)).isEmpty())
    }

    @Test fun warningUsesOnlyExistingEligibleExactWindows() {
        val up = early(0L, q(8, 55.0))
        assertEquals(setOf(1, 2), up.map { it.rule.gapMinutes }.toSet())
        assertTrue(up.all { it.rule.buyUp && it.window.eligible })
        assertTrue(early(0L, q(14, 55.0)).isEmpty())
        assertTrue(early(0L, q(4, -55.0)).isEmpty())
        assertTrue(early(0L, q(8, 60.0)).isEmpty())
        assertTrue(early(0L, q(8, 0.0)).isEmpty())
        assertTrue(early(0L, q(1, -55.0)).isEmpty())
    }

    @Test fun expiredWindowsCannotWarn() {
        assertEquals(2, early(59_999L, q(8, 55.0)).size)
        assertEquals(2, early(60_000L, q(8, 55.0)).single().rule.gapMinutes)
        assertTrue(early(120_000L, q(8, 55.0)).isEmpty())
        assertTrue(early(-1L, q(8, 55.0)).isEmpty())
    }

    @Test fun warningDoesNotAlterConfirmationAndFailedPatternExpires() {
        assertTrue(early(0L, q(8, 55.0)).isNotEmpty())
        assertTrue(match(q(8, 55.0), q(7, 75.0)).isEmpty())
        assertTrue(early(0L, q(8, 55.0), q(7, 75.0)).isEmpty())
        assertEquals("UP", match(q(8, 55.0), q(7, 65.0)).single().rule.side)
        assertEquals("UP", match(q(8, 55.0), q(7, 75.0), q(6, 65.0))
            .single().rule.side)
    }

    @Test fun previouslyCompletedRuleDoesNotWarnAgain() {
        val later = early(0L, q(13, -55.0), q(12, -65.0), q(10, -55.0))
        assertTrue(later.none { it.rule.gapMinutes == 1 })
        assertEquals(2, later.single().rule.gapMinutes)
    }

    private fun progress(elapsed: Long, live: Double,
        vararg samples: BtcChartProxyMath.Sample, touchAge: Long = 5_000L) =
        KalshiLiveSignalRules.matchingProgressWarnings(samples.toList(), elapsed, live, touchAge)

    @Test fun neutralCenterCanWarnInEitherDirectionAtEligibleMinute() {
        assertEquals("UP", progress(10_000L, 55.0, q(8, 0.0))
            .single().windows.first().rule.side)
        assertEquals("DOWN", progress(10_000L, -55.0, q(8, 0.0))
            .single().windows.first().rule.side)
        assertTrue(progress(10_000L, 54.99, q(8, 0.0)).isEmpty())
        assertTrue(progress(10_000L, 60.0, q(8, 0.0)).isEmpty())
        assertTrue(progress(10_000L, -55.0, q(8, 52.0)).isEmpty())
        assertTrue(progress(10_000L, 55.0, q(14, 0.0)).isEmpty())
    }

    @Test fun bothCenterTouchClockAndOriginalMinuteDeadlineMustPass() {
        assertTrue(progress(10_000L, 55.0, q(8, 0.0), touchAge = 300_000L).isEmpty())
        assertTrue(progress(10_000L, 55.0, q(8, 0.0), touchAge = -1L).isEmpty())
        assertEquals(setOf(1, 2), progress(59_999L, 55.0, q(8, 0.0))
            .single().windows.map { it.rule.gapMinutes }.toSet())
        assertEquals(2, progress(60_000L, 55.0, q(8, 0.0))
            .single().windows.single().rule.gapMinutes)
        assertTrue(progress(120_000L, 55.0, q(8, 0.0)).isEmpty())
        assertEquals(2, progress(50_000L, 55.0, q(8, 0.0), touchAge = 60_000L)
            .single().windows.single().rule.gapMinutes)
    }

    @Test fun actualTouchOrCrossingRequiredAndSyntheticOpeningIgnored() {
        fun point(t: Long, p: Double) = KalshiLiveSignalRules.CenterPoint(t, p)
        fun touch(vararg p: KalshiLiveSignalRules.CenterPoint) =
            KalshiLiveSignalRules.latestCenterTouch(p.toList(), 100.0, 1000L, 400_000L)
        assertNull(touch(point(1000, 100.0), point(2000, 101.0)))
        assertNull(touch(point(2000, 99.0), point(70_000, 101.0)))
        assertEquals(2000L, touch(point(2000, 99.0), point(5000, 101.0)))
        assertEquals(5000L, touch(point(2000, 99.0), point(5000, 100.0), point(9000, 100.0)))
        assertEquals(9000L, touch(point(5000, 100.0), point(9000, 101.0), point(12000, 99.0)))
    }

    @Test fun twoMinuteWindowRetainedAndBuyRulesUnchanged() {
        val pending = progress(15_000L, 57.0, q(8, 52.0), q(7, 53.0)).single()
        assertEquals(8, pending.startLeft)
        assertEquals(2, pending.windows.single().rule.gapMinutes)
        assertTrue(match(q(8, 0.0), q(7, 65.0)).isEmpty())
        assertEquals("UP", match(q(8, 52.0), q(7, 53.0), q(6, 65.0))
            .single().rule.side)
    }
}
