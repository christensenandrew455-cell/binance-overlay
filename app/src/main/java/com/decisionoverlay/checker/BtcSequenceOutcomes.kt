package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs

object BtcSequenceOutcomes {
    private val paths = listOf(listOf(-60,-70,-60), listOf(60,70,60), listOf(50,-60),
        listOf(50,60), listOf(-70,-60), listOf(70,60))

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
        paths.forEach { summarize(it) }
    }

    fun summary(csv: String): String = buildString {
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }
            .map { it.split(',') }.filter { it[1] == "all" }.toList()
        fun rate(w: Int, n: Int) = if (n == 0) "—" else String.format(Locale.US, "%.1f%%", 100.0*w/n)
        val windows = listOf("11–9" to 4..6, "8–5" to 7..10, "4–1" to 11..14)
        for (path in paths) {
            val matching = rows.filter { it[0] == path.joinToString(">") }
            val total = matching.first { it[3] == "all" }
            val hits = total[4].toInt()
            val up = path.last() > 0
            val col = if (up) 6 else 7
            appendLine(path.joinToString(" → ") + if (up) "   UP" else "   DOWN")
            appendLine("Hit rate ${rate(hits,total[2].toInt())} ($hits/${total[2]})")
            appendLine("Win rate ${rate(total[col].toInt(),hits)} (${total[col]}/$hits)")
            val stats = windows.map { (label, minutes) ->
                val group = matching.filter { (it[3].toIntOrNull() ?: 0) in minutes }
                Triple(label, group.sumOf { it[col].toInt() }, group.sumOf { it[4].toInt() })
            }
            val best = stats.filter { it.third > 0 }.maxWithOrNull(
                compareBy<Triple<String, Int, Int>> { it.second.toDouble()/it.third }.thenBy { it.third })
            appendLine(if (best == null) "Best window: —" else
                "Best: ${best.first} min left · ${rate(best.second,best.third)} (${best.second}/${best.third})")
            appendLine()
        }
    }
}
