package com.decisionoverlay.checker

internal object BtcMinuteRecorder {
    const val MARKETS = 100
    private val trends = listOf(15, 30, 60)
    val header = (listOf("market_start_ms", "minute", "close_time_ms", "btc_close",
        "target_btc", "distance_btc", "signed_chart_level", "is_settlement") +
        trends.map { "btc_return_${it}m_pct" } +
        (1..15).map { "chart_move_${it}m_points" }).joinToString(",")

    fun rows(start: Long, candles: Map<Long, BtcAcmMath.Candle>): String? {
        val step = BtcAcmMath.MINUTE_MS
        for (i in -60..14) if (candles[start + i * step] == null) return null
        val target = BtcAcmMath.quarterTargetCandle(candles, start)?.midpoint ?: return null
        fun level(price: Double, at: Long, left: Int): Double? {
            if (price == target) return 0.0
            // At settlement use the limiting signed endpoint of the capped proxy.
            if (left == 0) return if (price > target) 90.0 else -90.0
            val range = BtcAcmMath.averageRange(candles, at, 60) ?: return null
            return BtcChartProxyMath.signedStrength(price, target, range, left.toDouble())
                .takeIf { it.isFinite() }
        }
        val levels = arrayOfNulls<Double>(16)
        levels[0] = level(candles.getValue(start).open, start - step, 15)
        return buildString {
            for (minute in 1..15) {
                val closeTime = start + minute * step
                val at = closeTime - step
                val price = candles.getValue(at).close
                val current = level(price, at, 15 - minute)
                levels[minute] = current
                val fields = mutableListOf(start.toString(), minute.toString(), closeTime.toString(),
                    price.toString(), target.toString(), (price - target).toString(),
                    current?.toString().orEmpty(), (minute == 15).toString())
                for (window in trends) {
                    val prior = candles.getValue(at - window * step).close
                    fields.add(((price - prior) / prior * 100).toString())
                }
                for (lag in 1..15) {
                    val prior = levels.getOrNull(minute - lag)
                    fields.add(if (current != null && prior != null) (current - prior).toString() else "")
                }
                appendLine(fields.joinToString(","))
            }
        }
    }
}
