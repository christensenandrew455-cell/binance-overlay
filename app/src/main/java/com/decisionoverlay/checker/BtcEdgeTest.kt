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
        for (minute in 1..14) {
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

    private val names = listOf("1m close vs target", "5m momentum (2 bars)",
        "5m signed relative volume (60-bar baseline)", "15m EMA20 slope (3 bars)")
    private data class Setup(val band: Int, val left: Int, val indicators: Int, val positive: Int)
    private data class Counts(var hits: Int = 0, var up: Int = 0) {
        val wins get() = maxOf(up, hits - up)
        val winRate get() = wins.toDouble() / hits
        // Harmonic mean rewards setups that are both frequent and accurate.
        fun score(total: Int): Double {
            val coverage = hits.toDouble() / total
            return 2 * coverage * winRate / (coverage + winRate)
        }
    }

    fun report(markets: List<Market>): String {
        require(markets.size == 1000)
        val counts = mutableMapOf<Setup, Counts>()
        for (market in markets) {
            val seen = mutableSetOf<Setup>()
            for (row in market.rows) {
                var positive = 0
                var available = 0
                for (i in names.indices) {
                    if (row.x[i] != 0.0) available = available or (1 shl i)
                    if (row.x[i] > 0) positive = positive or (1 shl i)
                }
                var subset = available
                while (subset != 0) {
                    for (left in listOf(0, row.x.last().toInt())) {
                        val setup = Setup(row.band, left, subset, positive and subset)
                        if (seen.add(setup)) {
                            val c = counts.getOrPut(setup) { Counts() }
                            c.hits++
                            if (market.up) c.up++
                        }
                    }
                    subset = (subset - 1) and available
                }
            }
        }
        fun percent(value: Double) = String.format(Locale.US, "%.1f%%", 100 * value)
        return buildString {
            appendLine("1,000 completed BTC markets · historical setup rankings")
            appendLine("Hit rate = matching markets / 1,000. Each market counts once per setup. Setups overlap.")
            appendLine("Win rate = share of matches finishing in the more frequent direction. Rankings describe these same markets and need fresh-market validation.")
            appendLine("Band = Binance distance-to-target index. 50 is the middle band (magnitude below 60).")
            appendLine("Sorted by the harmonic mean of hit rate and win rate. Tied directions have no call.")
            for (timed in listOf(false, true)) {
                appendLine()
                appendLine(if (timed) "Best exact-minute setups" else "Best setups across any minute")
                val ranked = counts.entries.filter { (k, c) -> (k.left != 0) == timed && c.up * 2 != c.hits }
                    .sortedWith(compareByDescending<Map.Entry<Setup, Counts>> { it.value.score(markets.size) }
                        .thenByDescending { it.value.wins }.thenBy { it.key.band }.thenBy { it.key.left }
                        .thenBy { it.key.indicators }.thenBy { it.key.positive })
                if (ranked.isEmpty()) appendLine("No directional setups.")
                for ((index, entry) in ranked.take(20).withIndex()) {
                    val (k, c) = entry
                    val band = if (k.band == 50) "50 middle" else "${abs(k.band)} ${if (k.band > 0) "UP" else "DOWN"}"
                    val timing = if (timed) "${k.left} min left" else "1–14 min left"
                    val signals = names.indices.filter { k.indicators and (1 shl it) != 0 }.joinToString(" + ") {
                        names[it] + if (k.positive and (1 shl it) != 0) " up" else " down"
                    }
                    appendLine("${index + 1}. Band $band · $timing · $signals")
                    appendLine("Finish ${if (c.up * 2 > c.hits) "UP" else "DOWN"} · hits ${c.hits}/1000 (${percent(c.hits / 1000.0)}) · wins ${c.wins}/${c.hits} (${percent(c.winRate)})")
                }
            }
        }
    }
}
