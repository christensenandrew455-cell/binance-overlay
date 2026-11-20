package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs

object BtcSequenceOutcomes {
    private data class Count(var down: Int = 0, var up: Int = 0) {
        fun add(upWon: Boolean) { if (upWon) up++ else down++ }
    }
    private fun pct(n: Int, d: Int) = if (d == 0) "" else String.format(Locale.US, "%.1f", 100.0*n/d)
    private fun band(x: Double): Int {
        val magnitude = (abs(x)/10).toInt()*10
        return if (magnitude < 60) 50 else if (x < 0) -magnitude else magnitude
    }

    private fun window(left: Int) = when (left) {
        in 12..14 -> "14–12"
        in 9..11 -> "11–9"
        in 5..8 -> "8–5"
        else -> "4–1"
    }

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String = buildString {
        appendLine("threshold,kind,path,step_minutes_elapsed,finish_minutes_left,leg_gaps_minutes,total_duration_minutes,markets,down,up,final_down_pct,final_up_pct,scanned_markets,hit_pct")
        for (threshold in listOf(70,80,-70,-80)) {
            val sign = if (threshold > 0) 1 else -1
            val records = sortedMapOf<String,Count>()
            for (market in markets) {
                val samples = market.minutes
                val first = samples.indexOfFirst { sign * it.signedChartLevel >= abs(threshold) }
                if (first < 0) continue
                val seen = mutableSetOf<String>()
                fun add(kind: String, path: String, steps: String = "", left: String = "",
                        gaps: String = "", duration: String = "") {
                    val key = listOf(kind,path,steps,left,gaps,duration).joinToString(",")
                    if (seen.add(key)) records.getOrPut(key) { Count() }.add(market.upWon)
                }
                add("cohort","all touches")
                val touch = samples[first].minute
                add("first_touch","all touches", touch.toString(), (15-touch).toString())
                val levels = mutableListOf<Int>()
                val times = mutableListOf<Int>()
                var crossing: Int? = null
                for (i in first until samples.size) {
                    val obs = samples[i]
                    if (sign * obs.signedChartLevel <= 0.0) { crossing = obs.minute; break }
                    val level = band(obs.signedChartLevel)
                    if (level == levels.lastOrNull()) continue
                    levels.add(level)
                    times.add(obs.minute)
                    for (n in 2..minOf(3,levels.size)) {
                        val path = levels.takeLast(n).joinToString(">")
                        val minutes = times.takeLast(n)
                        val left = 15-obs.minute
                        val gaps = minutes.zipWithNext { a,b -> b-a }.joinToString("+")
                        val duration = (obs.minute-minutes.first()).toString()
                        add("exact",path,minutes.joinToString(">"),left.toString(),gaps,duration)
                        add("speed",path,gaps=gaps,duration=duration)
                        if (seen.add("first "+path)) {
                            add("sequence",path)
                            add("window",path,left=window(left))
                            add("minute",path,left=left.toString())
                        }
                    }
                }
                val end = crossing ?: if (market.upWon == (sign < 0)) 15 else null
                add("return_status",when {
                    crossing != null -> "returned by minute 14"
                    end != null -> "first crossed at settlement"
                    else -> "no observed return"
                })
                if (end != null) add("touch_to_target","target", "$touch>$end",(15-end).toString(),
                    (end-touch).toString(),(end-touch).toString())
            }
            for ((key,c) in records) {
                val n = c.down+c.up
                appendLine("$threshold,$key,$n,${c.down},${c.up},${pct(c.down,n)},${pct(c.up,n)},${markets.size},${pct(n,markets.size)}")
            }
        }
    }

    private fun rows(csv: String) = csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }
    private fun outcome(r: List<String>) = "DOWN ${r[10]}% (${r[8]}/${r[7]}) · UP ${r[11]}% (${r[9]}/${r[7]})"

    fun choices(csv: String) = rows(csv).filter { it[1] == "sequence" }
        .sortedByDescending { it[7].toInt() }.map { it[0] to it[2] }.toList()

    fun detail(csv: String, threshold: String, path: String): String = buildString {
        val group = rows(csv).filter { it[0] == threshold && it[2] == path }.toList()
        appendLine("${if (threshold.startsWith("-")) "" else "+"}$threshold touch · " + path.replace(">", " → "))
        appendLine(outcome(group.first { it[1] == "sequence" }))
        appendLine()
        appendLine("When the path first finished")
        val windows = group.filter { it[1] == "window" }
        for (r in windows) appendLine("${r[4]} min left: ${outcome(r)}")
        appendLine()
        appendLine("Exact minute of first finish")
        for (r in group.filter { it[1] == "minute" }.sortedByDescending { it[4].toInt() })
            appendLine("${r[4]} min left: ${outcome(r)}")
        appendLine()
        appendLine("Minutes between level changes")
        for (r in group.filter { it[1] == "speed" }.sortedByDescending { it[7].toInt() })
            appendLine("${r[5].replace("+"," then ")} min: ${outcome(r)}")
        appendLine()
        appendLine("Exact step times • elapsed minutes from open")
        for (r in group.filter { it[1] == "exact" }.sortedByDescending { it[7].toInt() })
            appendLine("M${r[3].replace(">"," → M")} · ${r[4]} min left · ${r[6]} min total\n${outcome(r)}")
    }

    fun summary(csv: String): String = buildString {
        val all = rows(csv).toList()
        appendLine("Choose PATTERN TIMING to compare exact minutes and move speeds.")
        appendLine("Windows use the first finish per market. Speed and exact-time rows count each matching market once per timing.")
        for (threshold in listOf("70","80","-70","-80")) {
            val group = all.filter { it[0] == threshold }
            val cohort = group.first { it[1] == "cohort" }
            appendLine("\n${if (threshold.startsWith("-")) "" else "+"}$threshold touched in ${cohort[7]} markets")
            appendLine(outcome(cohort))
            for (r in group.filter { it[1] == "return_status" })
                appendLine("${r[2]}: ${r[8]} DOWN / ${r[9]} UP")
            val paths = group.filter { it[1] == "sequence" }
            for ((side,col) in listOf("DOWN" to 8,"UP" to 9)) {
                appendLine("\nCommon paths in $side closes")
                for (p in paths.filter { it[col].toInt()>0 }.sortedByDescending { it[col].toInt() }.take(5)) {
                    appendLine(p[2].replace(">"," → ") + " · " + outcome(p))
                    val best = group.filter { it[1]=="window" && it[2]==p[2] && it[4]!="14–12" && it[7].toInt()>=10 }
                        .maxWithOrNull(compareBy<List<String>> { it[col].toDouble()/it[7].toInt() }.thenBy { it[7].toInt() })
                    appendLine(if (best==null) "No post-minute-3 window with 10 matches." else
                        "Best $side window: ${best[4]} min left · ${best[col]}/${best[7]} (${best[col+2]}%)")
                }
            }
        }
    }
}
