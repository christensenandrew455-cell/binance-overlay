package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs

object BtcSequenceOutcomes {
    private val levels = listOf(90, 80, 70, 60, 50, -60, -70, -80, -90)

    private fun band(value: Double): Int {
        val magnitude = (abs(value) / 10).toInt() * 10
        return when {
            magnitude < 60 -> 50
            value < 0 -> -magnitude
            else -> magnitude
        }
    }

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String = buildString {
        val ordered = markets.sortedBy { it.startMs }
        val samples = ordered.map { m -> m.minutes.map { band(it.signedChartLevel) } }
        val split = ordered.size * 7 / 10
        appendLine("sequence,sample,markets,first_completion_elapsed_minute,hits,hit_pct,up,down,up_pct,down_pct")
        fun summarize(path: List<Int>) {
            val hits = Array(3) { IntArray(15) }
            val ups = Array(3) { IntArray(15) }
            for ((i, series) in samples.withIndex()) {
                var step = 0
                for ((index, level) in series.withIndex()) {
                    if (level != path[step]) continue
                    if (++step < path.size) continue
                    val minute = index + 1
                    if (minute in 4..14) {
                        for (group in listOf(0, if (i < split) 1 else 2)) {
                            hits[group][minute]++
                            hits[group][0]++
                            if (ordered[i].upWon) {
                                ups[group][minute]++
                                ups[group][0]++
                            }
                        }
                    }
                    break
                }
            }
            val name = path.joinToString(">")
            fun pct(n: Int, total: Int) = if (total == 0) "" else
                String.format(Locale.US, "%.2f", 100.0 * n / total)
            for ((group, sample) in listOf("all", "older70", "newer30").withIndex()) {
                val total = listOf(ordered.size, split, ordered.size - split)[group]
                for (minute in 0..14) {
                    val n = hits[group][minute]
                    if (minute > 0 && n == 0) continue
                    val up = ups[group][minute]
                    appendLine(listOf(name, sample, total, if (minute == 0) "all" else minute,
                        n, pct(n,total), up, n-up, pct(up,n), pct(n-up,n)).joinToString(","))
                }
            }
        }
        for (a in levels) for (b in levels) if (a != b) summarize(listOf(a,b))
        for (a in levels) for (b in levels) for (c in levels)
            if (a != b && b != c) summarize(listOf(a,b,c))
    }
}
