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

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String = buildString {
        appendLine("threshold,kind,path,markets,down,up,share_of_down_pct,share_of_up_pct,final_down_pct")
        for (threshold in listOf(70,80)) {
            val total = Count()
            val records = sortedMapOf<String,Count>()
            for (market in markets) {
                val samples = market.minutes
                val first = samples.indexOfFirst { it.signedChartLevel >= threshold }
                if (first < 0) continue
                total.add(market.upWon)
                val seen = mutableSetOf<String>()
                // Only transitions observed between the first touch and the first
                // subsequent close at/below the actual BTC starting price.
                val path = mutableListOf<Int>()
                var returned = false
                for (i in first until samples.size) {
                    val level = samples[i].signedChartLevel
                    if (level <= 0.0) { returned = true; break }
                    val value = band(level)
                    if (path.lastOrNull() == value) continue
                    path.add(value)
                    for (length in 2..minOf(3,path.size))
                        seen.add("sequence," + path.takeLast(length).joinToString(">"))
                }
                val status = when {
                    returned -> "returned by minute 14"
                    !market.upWon -> "first crossed at settlement"
                    else -> "no observed return"
                }
                seen.add("return_status," + status)
                for (key in seen) records.getOrPut(key) { Count() }.add(market.upWon)
            }
            fun row(key: String, c: Count) {
                val n = c.up + c.down
                appendLine(listOf(threshold,key,n,c.down,c.up,pct(c.down,total.down),
                    pct(c.up,total.up),pct(c.down,n)).joinToString(","))
            }
            row("cohort,all touches",total)
            records.forEach { (key,c) -> row(key,c) }
        }
    }

    fun summary(csv: String): String = buildString {
        val rows = csv.lineSequence().drop(1).filter { it.isNotBlank() }.map { it.split(',') }.toList()
        appendLine("First touch or higher. Each market counts once per path. Target = starting BTC price; crossings checked at minute closes.")
        for (threshold in listOf("70","80")) {
            val group = rows.filter { it[0] == threshold }
            val total = group.first { it[1] == "cohort" }
            appendLine()
            appendLine("+${threshold} reached in ${total[3]} markets")
            appendLine("Final DOWN: ${total[4]}   UP: ${total[5]}")
            for (r in group.filter { it[1] == "return_status" })
                appendLine("${r[2]}: ${r[4]} DOWN / ${r[5]} UP")
            val paths = group.filter { it[1] == "sequence" }
            for ((label, col) in listOf("DOWN" to 4, "UP" to 5)) {
                appendLine()
                appendLine("Most common before target in $label closes")
                val top = paths.filter { it[col].toInt() > 0 }.sortedWith(
                    compareByDescending<List<String>> { it[col].toInt() }
                    .thenBy { it[9-col].toInt() }.thenBy { it[2] }).take(5)
                if (top.isEmpty()) appendLine("No observed two- or three-step paths.")
                for (r in top) {
                    appendLine(r[2].replace(">", " → "))
                    appendLine("DOWN ${r[4]}/${total[4]} (${r[6].ifEmpty { "—" }}%) · UP ${r[5]}/${total[5]} (${r[7].ifEmpty { "—" }}%)")
                }
            }
        }
    }
}
