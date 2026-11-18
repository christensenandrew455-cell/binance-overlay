package com.decisionoverlay.checker

import kotlin.math.max

/**
 * Actual 15m time axis; requires only completed candles for historic ACM.
 * At each past timestamp, recompute the rolling ACM60 with no lookahead.
 * From NOW until expiration, project with the latest ACM60. The actual
 * threshold uses floating-point minutes left, not ceil(minutesLeft).
 *
 * Constant ACM creates TWO SLOPING STRAIGHT LINES. If ACM changes, their
 * historic shape bends. No invented nonlinear time exponent is applied.
 */
object BtcAcmCountdown {
    private const val MINUTE = BtcAcmMath.MINUTE_MS
    data class Point(
        val at: Long, val upper: Double, val lower: Double,
        val btcPrice: Double?, val projected: Boolean
    )
    fun boundaries(target: Double, acmPct: Double, msRemaining: Long): Pair<Double, Double> {
        require(target.isFinite() && target > 0.0 && acmPct.isFinite() && acmPct >= 0.0)
        val minutesLeft = max(0L, msRemaining).toDouble() / MINUTE
        val change = acmPct * minutesLeft * BtcAcmSignal.MULTIPLIER / 100.0
        return (target * (1.0 + change)) to (target * (1.0 - change))
    }
    fun series(
        bars: Map<Long, BtcAcmMath.Candle>, target: Double,
        marketEnd: Long, now: Long, currentAcm: Double, spot: Double
    ): List<Point> {
        if (marketEnd <= now || now < marketEnd - 15L * MINUTE ||
            !currentAcm.isFinite() || currentAcm <= 0.0 ||
            target <= 0.0 || spot <= 0.0) return emptyList()
        val start = marketEnd - 15L * MINUTE
        val pts = ArrayList<Point>()
        // Close of a 1-minute bar is available only AFTER that bar has closed.
        // No future candles can influence historical past points.
        var candleAt = start
        val lastCompletedBar = now / MINUTE * MINUTE - MINUTE
        while (candleAt <= lastCompletedBar && candleAt < marketEnd) {
            val closedAt = candleAt + MINUTE
            val c = bars[candleAt]
            val acm = BtcAcmMath.averageRange(bars, candleAt, 60)
            if (c != null && acm != null && acm > 0.0) {
                val bound = boundaries(target, acm, marketEnd - closedAt)
                pts.add(Point(closedAt, bound.first, bound.second, c.close, false))
            }
            candleAt += MINUTE
        }
        val nowBound = boundaries(target, currentAcm, marketEnd - now)
        pts.add(Point(now, nowBound.first, nowBound.second, spot, false))
        // Clearly mark projection, which assumes ACM stays at its latest value.
        // As fresh candles close, the projection will be rebuilt.
        var projectedTime = now + 10_000L
        while (projectedTime < marketEnd) {
            val bound = boundaries(target, currentAcm, marketEnd - projectedTime)
            pts.add(Point(projectedTime, bound.first, bound.second, null, true))
            projectedTime += 10_000L
        }
        pts.add(Point(marketEnd, target, target, null, true))
        return pts.sortedBy { it.at }
    }
}
