package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BtcAcmProfitMathTest {
    @Test fun feeRoundsUpPerOrderNotFlatPercentOfProfit() {
        assertEquals(1.75, BtcAcmProfitMath.takerFee(0.50, 100), 0.00001)
        assertEquals(0.02, BtcAcmProfitMath.takerFee(0.50, 1), 0.00001)
        assertEquals(0.63, BtcAcmProfitMath.takerFee(0.90, 100), 0.00001)
    }
    @Test fun ninetyCentPriceSizesToTenDollarBudget() {
        val win = BtcAcmProfitMath.tenDollarPosition(0.90, true)!!
        val loss = BtcAcmProfitMath.tenDollarPosition(0.90, false)!!
        assertEquals(11, win.contracts)
        assertEquals(0.07, win.fee, 0.00001)
        assertEquals(9.97, win.cost, 0.00001)
        assertEquals(1.03, win.pnl, 0.00001)
        assertEquals(-9.97, loss.pnl, 0.00001)
        assertEquals(9.97 / 11, BtcAcmProfitMath.breakEvenWinRate(0.90, 11), 0.00001)
    }
    @Test fun sixtyCentPriceHasHigherWinnerPayoutAndLargerLossRisk() {
        val win = BtcAcmProfitMath.tenDollarPosition(0.60, true)!!
        assertEquals(16, win.contracts)
        assertEquals(0.27, win.fee, 0.00001)
        assertEquals(9.87, win.cost, 0.00001)
        assertEquals(6.13, win.pnl, 0.00001)
        assertEquals(-9.87, BtcAcmProfitMath.tenDollarPosition(0.60,false)!!.pnl,0.00001)
    }
    @Test fun invalidQuotesAreNotTreatedAsExchangesTrades() {
        assertNull(BtcAcmProfitMath.tenDollarPosition(0.0, true))
        assertNull(BtcAcmProfitMath.tenDollarPosition(1.0, true))
        assertNull(BtcAcmProfitMath.tenDollarPosition(Double.NaN, true))
    }
}
