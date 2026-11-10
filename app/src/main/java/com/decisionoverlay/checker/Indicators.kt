package com.decisionoverlay.checker

object Indicators {
    fun ema(values: List<Double>, period: Int): Double? = emaSeries(values, period).lastOrNull()

    /** Pandas ewm(span=period, adjust=false): seed with the first observation. */
    fun emaSeriesFromFirst(values: List<Double>, period: Int): List<Double> {
        if (values.isEmpty() || period <= 0) return emptyList()
        val k = 2.0 / (period + 1)
        var ema = values.first()
        val out = ArrayList<Double>(values.size)
        out.add(ema)
        for (index in 1 until values.size) {
            ema = values[index] * k + ema * (1 - k)
            out.add(ema)
        }
        return out
    }

    fun emaSeries(values: List<Double>, period: Int): List<Double> {
        if (values.size < period || period <= 0) return emptyList()
        val k = 2.0 / (period + 1)
        var ema = values.take(period).average()
        val out = ArrayList<Double>(values.size - period + 1)
        out.add(ema)
        for (index in period until values.size) {
            ema = values[index] * k + ema * (1 - k)
            out.add(ema)
        }
        return out
    }

    /** Wilder RSI. Returns null until there are period + 1 closes. */
    fun rsi(closes: List<Double>, period: Int = 14): Double? {
        if (closes.size < period + 1) return null
        var gain = 0.0
        var loss = 0.0
        for (index in 1..period) {
            val delta = closes[index] - closes[index - 1]
            if (delta >= 0) gain += delta else loss -= delta
        }
        var avgGain = gain / period
        var avgLoss = loss / period
        for (index in (period + 1) until closes.size) {
            val delta = closes[index] - closes[index - 1]
            val up = if (delta > 0) delta else 0.0
            val down = if (delta < 0) -delta else 0.0
            avgGain = (avgGain * (period - 1) + up) / period
            avgLoss = (avgLoss * (period - 1) + down) / period
        }
        if (avgLoss == 0.0) return 100.0
        val rs = avgGain / avgLoss
        return 100.0 - (100.0 / (1.0 + rs))
    }

    data class Macd(
        val line: Double,
        val signal: Double,
        val histogram: Double,
        val previousHistogram: Double?,
    )

    fun macd(closes: List<Double>, fast: Int = 12, slow: Int = 26, signalPeriod: Int = 9): Macd? {
        if (closes.size < slow + signalPeriod) return null
        val emaFast = emaSeries(closes, fast)
        val emaSlow = emaSeries(closes, slow)
        if (emaFast.isEmpty() || emaSlow.isEmpty()) return null
        val offset = emaFast.size - emaSlow.size
        val macdLine = ArrayList<Double>(emaSlow.size)
        for (index in emaSlow.indices) {
            macdLine.add(emaFast[index + offset] - emaSlow[index])
        }
        val signalSeries = emaSeries(macdLine, signalPeriod)
        if (signalSeries.isEmpty()) return null
        val line = macdLine.last()
        val signal = signalSeries.last()
        val histogram = line - signal
        val previous = if (signalSeries.size >= 2 && macdLine.size >= 2) {
            macdLine[macdLine.size - 2] - signalSeries[signalSeries.size - 2]
        } else {
            null
        }
        return Macd(line, signal, histogram, previous)
    }

    fun volumeRatio(candles: List<Candle>): Double? {
        if (candles.size < 6) return null
        val baseline = candles.dropLast(1).takeLast(20).map { it.volume }
        val average = baseline.average()
        if (average <= 0.0) return null
        return candles.last().volume / average
    }
}
