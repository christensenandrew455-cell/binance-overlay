package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcAcmSignalTest {
    @Test fun qualifyingAboveTargetGivesBuyYesWithFeeChecked() {
        val d = BtcAcmSignal.evaluate(100.05, 100.0, 0.02, 240.0, 0.65, 0.65)
        assertTrue(d.buyEligible)
        assertEquals("YES", d.direction)
        assertEquals(4, d.minutesBucket)
        assertEquals(0.02, d.requiredPct, 1e-9)
        assertTrue(d.breakEven > 0.65)
        assertTrue(d.rawEdgePercentagePoints > 5.0)
        assertTrue(d.contracts >= 1)
    }

    @Test fun qualifyingBelowTargetGivesBuyNoNotYes() {
        val d = BtcAcmSignal.evaluate(99.95, 100.0, 0.02, 240.0, 0.90, 0.63)
        assertTrue(d.buyEligible)
        assertEquals("NO", d.direction)
        assertEquals(0.63, d.ask!!, 1e-9)
    }

    @Test fun priceMustCrossTheAcmThreshold() {
        val d = BtcAcmSignal.evaluate(100.01, 100.0, 0.02, 240.0, 0.65, 0.65)
        assertFalse(d.buyEligible)
        assertTrue(d.reason.contains("not reached"))
    }

    @Test fun priceMustBeInHistoricalSixtyToSixtyNineCentBand() {
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            240.0, 0.70, 0.65).buyEligible)
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            240.0, 0.59, 0.65).buyEligible)
        assertTrue(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            240.0, 0.69, 0.65).buyEligible)
    }

    @Test fun noQuoteNoCandleOrFinalMinuteMeansNoBuy() {
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            240.0, null, null).buyEligible)
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, Double.NaN,
            240.0, 0.65, 0.65).buyEligible)
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            50.0, 0.65, 0.65).buyEligible)
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            540.0, 0.65, 0.65).buyEligible)
    }

    @Test fun noBuyWhenMarketIsOutsideAllowedHoursOrAtTarget() {
        assertFalse(BtcAcmSignal.evaluate(100.05, 100.0, 0.02,
            240.0, 0.65, 0.65, false).buyEligible)
        assertFalse(BtcAcmSignal.evaluate(100.0, 100.0, 0.02,
            240.0, 0.65, 0.65).buyEligible)
    }
}
