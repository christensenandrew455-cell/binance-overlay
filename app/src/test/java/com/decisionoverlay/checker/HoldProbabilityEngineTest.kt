package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

class HoldProbabilityEngineTest {
    @Test fun allCoinsUsePercentageBandsRatherThanFiftyDollarPriceSteps() {
        assertEquals(0.05, HoldProbabilityEngine.PERCENT_STEP, 0.00001)
        assertEquals(0, HoldProbabilityEngine.bandForPercent(0.0))
        assertEquals(0, HoldProbabilityEngine.bandForPercent(0.0499))
        assertEquals(1, HoldProbabilityEngine.bandForPercent(0.05))
        assertEquals(2, HoldProbabilityEngine.bandForPercent(0.10))
        assertEquals(30, HoldProbabilityEngine.bandForPercent(10.0))
        // BTC 0.1% move and DOGE 0.1% move go in exactly the same bucket.
        assertEquals(HoldProbabilityEngine.bandForPercent(
            HoldProbabilityEngine.distancePercent(100.1, 100.0)),
            HoldProbabilityEngine.bandForPercent(
                HoldProbabilityEngine.distancePercent(0.1001, 0.1000)))
        assertTrue(HoldProbabilityEngine.bandLabel(1).contains("%"))
        assertEquals(9, HoldProbabilityEngine.coins.size)
    }

    @Test fun yesWinsTiesAndNoRequiresStrictlyBelow() {
        assertEquals(1, HoldProbabilityEngine.leadingSide(101.0, 100.0))
        assertEquals(-1, HoldProbabilityEngine.leadingSide(99.0, 100.0))
        assertEquals(0, HoldProbabilityEngine.leadingSide(100.0, 100.0))
        assertTrue(HoldProbabilityEngine.held(1, 100.0, 100.0))
        assertFalse(HoldProbabilityEngine.held(-1, 100.0, 100.0))
        assertTrue(HoldProbabilityEngine.held(-1, 99.0, 100.0))
        assertFalse(HoldProbabilityEngine.held(1, 99.0, 100.0))
    }

    @Test fun retailOneContractFeeIsConservativelyRounded() {
        assertEquals(.02, HoldProbabilityEngine.estimatedFee(.60), 0.000001)
        assertEquals(.62, HoldProbabilityEngine.breakEven(.60), 0.000001)
    }

    @Test fun onePerfectPredictionNeverTriggersBuy() {
        val sample = HoldProbabilityEngine.Rate(1, 1)
        val answer = HoldProbabilityEngine.decide(sample, sample, .40, 15.0)
        assertEquals("WAIT", answer.label)
        assertTrue(answer.why.contains("100"))
    }

    @Test fun strongEvidenceCanSignalAtBeginningAndEnd() {
        val sample = HoldProbabilityEngine.Rate(300, 285)
        assertEquals("POTENTIAL BUY",
            HoldProbabilityEngine.decide(sample, sample, .60, 15.0).label)
        assertEquals("POTENTIAL BUY",
            HoldProbabilityEngine.decide(sample, sample, .60, 10.0).label)
        assertEquals("POTENTIAL BUY",
            HoldProbabilityEngine.decide(sample, sample, .60, 1.0).label)
        assertEquals("WAIT",
            HoldProbabilityEngine.decide(sample, sample, .60, 0.0).label)
    }

    @Test fun seventyPercentDoesNotGuaranteeSixtyCentIsValue() {
        val sample = HoldProbabilityEngine.Rate(100, 70)
        assertEquals("WAIT",
            HoldProbabilityEngine.decide(sample, sample, .60, 12.0).label)
    }

    @Test fun minimumGapThresholdUsesIndependentHistoricalSplits() {
        val key = HoldProbabilityEngine.Key(8, 2, 1)
        val old = HoldProbabilityEngine.Rate(300, 290)
        val newer = HoldProbabilityEngine.Rate(300, 288)
        val historical = HoldProbabilityEngine.Historical("BTC", 2400, 1200, 1200,
            0, 100000, mapOf(key to old), mapOf(key to newer))
        assertEquals(.10, historical.threshold(8, 1, .60)!!, 0.00001)
        assertNull(historical.threshold(8, -1, .60))
        assertNull(historical.threshold(7, 1, .60))
        assertNull(historical.threshold(8, 1, .98))
    }

    @Test fun trendAlignedBucketIsSeparateAndNotPooledWithBaseCases() {
        val baseline = HoldProbabilityEngine.Key(5, 1, 1, 0)
        val aligned = HoldProbabilityEngine.Key(5, 1, 1, 1)
        val historic = HoldProbabilityEngine.Historical("BTC", 2400, 1200, 1200,
            0, 100000, mapOf(
                baseline to HoldProbabilityEngine.Rate(300, 180),
                aligned to HoldProbabilityEngine.Rate(120, 110)
            ), mapOf(
                baseline to HoldProbabilityEngine.Rate(300, 175),
                aligned to HoldProbabilityEngine.Rate(120, 110)
            ))
        assertEquals(0.05, historic.threshold(5, 1, .60, 1)!!, 0.00001)
        assertNull(historic.threshold(5, 1, .60, 0))
    }

    @Test fun chanceGoalsUsePriceGapPercentAndNeverChooseFromNewerSample() {
        val old = mapOf(
            HoldProbabilityEngine.Key(9, 0, 1) to HoldProbabilityEngine.Rate(100, 55),
            HoldProbabilityEngine.Key(9, 1, 1) to HoldProbabilityEngine.Rate(80, 72),
            HoldProbabilityEngine.Key(9, 2, 1) to HoldProbabilityEngine.Rate(50, 46))
        val newer = mapOf(
            HoldProbabilityEngine.Key(9, 0, 1) to HoldProbabilityEngine.Rate(110, 90),
            HoldProbabilityEngine.Key(9, 1, 1) to HoldProbabilityEngine.Rate(90, 56),
            HoldProbabilityEngine.Key(9, 2, 1) to HoldProbabilityEngine.Rate(50, 30))
        val history = HoldProbabilityEngine.Historical("BTC", 2400, 1200, 1200,
            0, 100000L, old, newer)
        val sixty = history.chanceGap(9, 1, .60)!!
        assertEquals(0.0, sixty.minimumGapPct, .00001)
        assertEquals(176, sixty.newer.successes) // 90 + 56 + 30
        val ninety = history.chanceGap(9, 1, .90)!!
        assertEquals(.05, ninety.minimumGapPct, .00001)
        assertEquals(140, ninety.newer.count)
        // Newer real rate = 86/140, NOT a falsely advertised 90%.
        assertEquals(86.0 / 140, ninety.newer.probability, .00001)
        assertNull(history.chanceGap(9, -1, .60))
        assertTrue(history.chanceTable(.90).contains("9m"))
        assertTrue(history.chanceTable(.90).contains("61.4%"))
    }

    @Test fun oneCaseCannotProduceFakeNinetyPercentConfidence() {
        val key = HoldProbabilityEngine.Key(15, 30, 1)
        val history = HoldProbabilityEngine.Historical("DOGE", 2400, 1200, 1200,
            0, 100000L,
            mapOf(key to HoldProbabilityEngine.Rate(1, 1)),
            mapOf(key to HoldProbabilityEngine.Rate(1, 1)))
        assertNull(history.chanceGap(15, 1, .90))
    }

    @Test fun percentStatisticsCachedSafelyInV2Json() {
        val key = HoldProbabilityEngine.Key(15, 0, 1, 1)
        val historic = HoldProbabilityEngine.Historical("SOL", 2400, 1199, 1200,
            1, 100000, mapOf(key to HoldProbabilityEngine.Rate(105, 81)),
            mapOf(key to HoldProbabilityEngine.Rate(120, 94)))
        val parsed = HoldProbabilityEngine.Historical.parse(historic.json())
        assertEquals(105, parsed.rate(key, false).count)
        assertEquals(81, parsed.rate(key, false).successes)
        assertEquals(120, parsed.rate(key, true).count)
        assertEquals(94, parsed.rate(key, true).successes)
    }
}
