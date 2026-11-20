package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.*

internal object BtcEdgeTest {
    data class Row(val band: Int, val x: DoubleArray)
    data class Market(val up: Boolean, val rows: List<Row>)
    private const val M = 60_000L

    fun market(start: Long, target: Double, up: Boolean, bars: Map<Long, BtcAcmMath.Candle>,
               levels: List<Double>): Market? {
        // All features end at or before the decision timestamp. Keep markets
        // that never reach an eligible band in the hit-rate denominator.
        val rows = mutableListOf<Row>()
        for (minute in 3..14) {
            val end = start + minute*M
            val latest = bars[end-M] ?: return null
            val left = 15-minute
            val body = (2..61).sumOf { i ->
                val c = bars[end-i*M] ?: return null
                abs(c.close-c.open)
            } / 60
            if (body <= 0) continue
            val five = end / (5*M) * (5*M)
            val first = bars[five-10*M] ?: return null
            val last = bars[five-M] ?: return null
            var current = 0.0
            var prior = 0.0
            for (i in 1..305) {
                val v = bars[five-i*M]?.volume ?: return null
                if (!v.isFinite() || v < 0) return null
                if (i<=5) current += v else prior += v
            }
            if (prior <= 0) continue
            val fiveOpen = bars.getValue(five-5*M).open
            val signedVolume = sign(last.close-fiveOpen)*current/(prior/60)
            val quarter = end/(15*M)*(15*M)
            fun close(back: Int) = bars[quarter-(15L*back+1)*M]?.close
            var ema = (3..22).sumOf { close(it) ?: return null }/20
            val seed = ema
            for (i in 2 downTo 0) ema += (2.0/21)*((close(i) ?: return null)-ema)
            val level = levels[minute-1]
            val magnitude = (abs(level)/10).toInt()*10
            val band = if (magnitude<60) 50 else if (level<0) -magnitude else magnitude
            val features = doubleArrayOf((latest.close-target)/(body*left),
                100*(last.close/first.open-1), signedVolume, 100*(ema/seed-1), left.toDouble())
            if (features.all { it.isFinite() }) rows.add(Row(band, features))
        }
        return if (rows.isEmpty()) null else Market(up, rows)
    }

    private fun probability(x: DoubleArray, w: DoubleArray): Double {
        var score = w.last()
        for (j in x.indices) score += w[j]*x[j]
        return 1/(1+exp(-score.coerceIn(-40.0,40.0)))
    }

    private data class Call(val up: Boolean, val band: Int, val predicted: Double, val win: Boolean)

    fun report(markets: List<Market>): String {
        require(markets.size == 1000)
        val train = markets.take(700).flatMap { m -> m.rows.map { it.x to if (m.up) 1.0 else 0.0 } }
        val mean = DoubleArray(5) { j -> train.map { it.first[j] }.average() }
        val sd = DoubleArray(5) { j -> sqrt(train.sumOf { (it.first[j]-mean[j]).pow(2) }/train.size).coerceAtLeast(1e-8) }
        fun normalize(x: DoubleArray) = DoubleArray(5) { ((x[it]-mean[it])/sd[it]).coerceIn(-8.0,8.0) }
        val normalized = train.map { normalize(it.first) to it.second }
        val w = DoubleArray(6)
        repeat(400) {
            val gradient = DoubleArray(6)
            for ((x,y) in normalized) {
                val error = y-probability(x,w)
                for (j in x.indices) gradient[j] += error*x[j]
                gradient[5] += error
            }
            for (j in w.indices) w[j] += 0.25*(gradient[j]/train.size - if (j<5) 0.001*w[j] else 0.0)
        }
        val calls = mutableListOf<Call>()
        for (market in markets.drop(700)) {
            for (row in market.rows) {
                val band = abs(row.band)
                if (band !in listOf(50,60,70)) continue
                val p = probability(normalize(row.x),w)
                val up = if (band==50) p>=0.5 else row.band>0
                val predicted = 100*(if (up) p else 1-p)
                if (predicted < band+20) continue
                calls.add(Call(up,band,predicted,up==market.up))
                break
            }
        }
        return buildString {
            appendLine("700 older markets fitted · 300 newest tested · first qualifying call per market")
            appendLine("Baseline = Binance candle band. Outcome = BTC close versus target. Gap is in percentage points.")
            fun group(label: String, set: List<Call>) {
                appendLine()
                appendLine(label)
                if (set.isEmpty()) { appendLine("0/300 hits (0%). Win rate and gap unavailable."); return }
                val wins = set.count { it.win }
                val rate = 100.0*wins/set.size
                val base = set.map { it.band }.average()
                val forecast = set.map { it.predicted }.average()
                appendLine(String.format(Locale.US,"%d/300 hits (%.1f%%) · %d/%d wins (%.1f%%)",set.size,set.size/3.0,wins,set.size,rate))
                appendLine(String.format(Locale.US,"Average band %.1f%% · predicted %.1f%% · actual minus band %+.1f points · predicted minus band %+.1f points",base,forecast,rate-base,forecast-base))
                appendLine(if (rate-base>=20) "Reached +20 points in this sample." else "Below +20 points in this sample.")
            }
            group("All signals",calls)
            for (band in listOf(50,60,70)) for (up in listOf(true,false))
                group("${if (up) "UP" else "DOWN"} · $band% band", calls.filter { it.band==band && it.up==up })
        }
    }
}
