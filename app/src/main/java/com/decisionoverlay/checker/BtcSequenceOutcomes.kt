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
        appendLine("sequence,sample,markets,first_completion_elapsed_minute,hits,hit_pct,up,down,up_pct,down_pct,gap_1_minutes,gap_2_minutes")
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
                        n, pct(n,total), up, n-up, pct(up,n), pct(n-up,n), "any", if (path.size == 3) "any" else "").joinToString(","))
                }
            }
        }
        for (a in levels) for (b in levels) if (a != b) summarize(listOf(a,b))
        for (a in levels) for (b in levels) for (c in levels)
            if (a != b && b != c) summarize(listOf(a,b,c))
        appendTimedRows(ordered, samples, this)
    }

    private data class TimedPath(val a: Int, val b: Int, val c: Int?, val gap1: Int, val gap2: Int)
    private class Counts {
        var hits = 0
        var up = 0
        val byMinute = IntArray(15)
        val upByMinute = IntArray(15)
    }

    private fun appendTimedRows(markets: List<BinanceTwentySevenPatternReport.Market>,
        samples: List<List<Int>>, out: StringBuilder) {
        val counts = linkedMapOf<TimedPath, Counts>()
        for ((index, series) in samples.withIndex()) {
            val seen = HashSet<TimedPath>()
            fun record(path: TimedPath, end: Int) {
                if (!seen.add(path) || end + 1 < 4) return
                val count = counts.getOrPut(path) { Counts() }
                count.hits++
                count.byMinute[end + 1]++
                if (markets[index].upWon) {
                    count.up++
                    count.upByMinute[end + 1]++
                }
            }
            // End minute first: each exact gap profile counts once per market.
            for (end in series.indices) for (mid in 0 until end) {
                if (series[mid] == series[end]) continue
                record(TimedPath(series[mid], series[end], null, end-mid, 0), end)
                for (start in 0 until mid) {
                    if (series[start] != series[mid])
                        record(TimedPath(series[start], series[mid], series[end], mid-start, end-mid), end)
                }
            }
        }
        fun pct(n: Int, d: Int) = (kotlin.math.round(10000.0 * n / d) / 100.0).toString()
        for ((path, countsForPath) in counts) {
            val label = listOfNotNull(path.a, path.b, path.c).joinToString(">")
            for (minute in 0..14) {
                val hits = if (minute == 0) countsForPath.hits else countsForPath.byMinute[minute]
                if (hits == 0) continue
                val up = if (minute == 0) countsForPath.up else countsForPath.upByMinute[minute]
                out.appendLine(listOf(label, "all", markets.size, if (minute == 0) "all" else minute,
                    hits, pct(hits, markets.size), up, hits-up, pct(up,hits), pct(hits-up,hits),
                    path.gap1, if (path.c == null) "" else path.gap2).joinToString(","))
            }
        }
    }
}
