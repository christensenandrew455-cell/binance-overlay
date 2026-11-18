package com.decisionoverlay.checker

import kotlin.math.ceil

/** Kalshi's general taker fee, rounded UP to the nearest cent per order.
 * No settlement fee; single $10 buy held to settlement; whole contracts only.
 * Special market fee multipliers, split fills and promotions may differ.
 */
object BtcAcmProfitMath {
    data class Position(
        val contracts: Int,
        val fee: Double,
        val cost: Double,
        val pnl: Double
    )
    fun takerFee(price: Double, contracts: Int): Double {
        require(price.isFinite() && price in 0.0..1.0 && contracts >= 0)
        return ceil(0.07 * contracts * price * (1.0-price) * 100.0 - 1e-9) / 100.0
    }
    fun tenDollarPosition(price: Double, wins: Boolean): Position? {
        if (!price.isFinite() || price < 0.01 || price > 0.99) return null
        var count = 0
        var fee = 0.0
        while (count < 1000) {
            val next = count + 1
            val nextFee = takerFee(price, next)
            if (next*price + nextFee > 10.0 + 1e-9) break
            count = next
            fee = nextFee
        }
        if (count == 0) return null
        val cost = count*price + fee
        return Position(count, fee, cost,
            (if (wins) count.toDouble() else 0.0) - cost)
    }
    fun breakEvenWinRate(price: Double, contracts: Int): Double =
        (contracts*price + takerFee(price, contracts)) / contracts
}
