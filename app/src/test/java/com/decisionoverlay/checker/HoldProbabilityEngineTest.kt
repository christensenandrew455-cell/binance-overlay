package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldProbabilityEngineTest {
    @Test fun btcUsesExactFiftyDollarBands() {
        assertEquals(50.0, HoldProbabilityEngine.dollarStep("BTC"), 0.00001)
        assertEquals(0, HoldProbabilityEngine.bandFor("BTC", 0.0))
        assertEquals(0, HoldProbabilityEngine.bandFor("BTC", 49.99))
        assertEquals(1, HoldProbabilityEngine.bandFor("BTC", 50.0))
        assertEquals(2, HoldProbabilityEngine.bandFor("BTC", 100.0))
        assertEquals(20, HoldProbabilityEngine.bandFor("BTC", 5000.0))
        assertTrue(HoldProbabilityEngine.dollarStep("DOGE") < 1.0)
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

    @Test fun feeBreakEvenIsHigherThanAsk() {
        val fee = HoldProbabilityEngine.estimatedFee(.60)
        assertTrue(fee > 0.0)
        assertTrue(HoldProbabilityEngine.breakEven(.60) > .60)
    }

    @Test fun oneSampleCannotTriggerBuy() {
        val tiny = HoldProbabilityEngine.Rate(1, 1)
        val output = HoldProbabilityEngine.decide(tiny, tiny, .10, 4.0)
        assertEquals("WAIT", output.label)
        assertTrue(output.why.contains("100"))
    }

    @Test fun highObservedRateWithStrongEvidenceCanSignal() {
        val old = HoldProbabilityEngine.Rate(300, 285)
        val newer = HoldProbabilityEngine.Rate(300, 285)
        val result = HoldProbabilityEngine.decide(old, newer, .60, 4.0)
        assertEquals("POTENTIAL BUY", result.label)
        assertTrue(result.lower95 > HoldProbabilityEngine.breakEven(.60))
    }

    @Test fun aSeventyPercentEstimateIsNotAutomaticEdgeAtSixtyCentAsk() {
        val old = HoldProbabilityEngine.Rate(100, 70)
        val newer = HoldProbabilityEngine.Rate(100, 70)
        val result = HoldProbabilityEngine.decide(old, newer, .60, 4.0)
        assertEquals("WAIT", result.label)
    }

    @Test fun onlyAllowsFourToFiveMinuteLiveWindow() {
        val evidence = HoldProbabilityEngine.Rate(300, 290)
        assertEquals("WAIT", HoldProbabilityEngine.decide(evidence, evidence, .50, 5.1).label)
        assertEquals("WAIT", HoldProbabilityEngine.decide(evidence, evidence, .50, 3.0).label)
        assertEquals("POTENTIAL BUY",
            HoldProbabilityEngine.decide(evidence, evidence, .50, 4.0).label)
    }

    @Test fun cachedRatesRoundTripToJson() {
        val k = HoldProbabilityEngine.Key(5, 1, 1)
        val historic = HoldProbabilityEngine.Historical(
            "BTC", 2400, 1200, 1200, 0, 100000,
            mapOf(k to HoldProbabilityEngine.Rate(107, 79)),
            mapOf(k to HoldProbabilityEngine.Rate(120, 88))
        )
        val parsed = HoldProbabilityEngine.Historical.parse(historic.toJson())
        assertEquals(107, parsed.rate(k, false).count)
        assertEquals(79, parsed.rate(k, false).successes)
        assertEquals(120, parsed.rate(k, true).count)
        assertEquals(88, parsed.rate(k, true).successes)
    }
}
