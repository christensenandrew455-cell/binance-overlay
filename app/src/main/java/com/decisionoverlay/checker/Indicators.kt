package com.decisionoverlay.checker

object Indicators {
    /** Pandas ewm(span=period, adjust=false): seed with the first observation. */
    fun emaSeriesFromFirst(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty() || period <= 0) return emptyList()
        val multiplier = 2.0 / (period + 1)
        var ema = values.first()
        val result = ArrayList<Double>(values.size)
        result.add(ema)
        for (index in 1 until values.size) {
            ema = values[index] * multiplier + ema * (1.0 - multiplier)
            result.add(ema)
        }
        return result
    }

    fun ema(values: List<Double>, period: Int): Double? =
        emaSeriesFromFirst(values, period).lastOrNull()
}
