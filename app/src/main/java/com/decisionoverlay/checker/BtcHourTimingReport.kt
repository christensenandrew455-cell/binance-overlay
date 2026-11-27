package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.sqrt

/** 100-day, one-minute 50-to-60 timing study. Historical research only. */
internal object BtcHourTimingReport {
    private val zone = ZoneId.of("America/New_York")
    private data class Signal(val start: Long, val hour: Int, val left: Int, val down: Boolean, val won: Boolean)
    private data class Cell(val hour: Int, val left: Int)
    private fun pct(a: Int,b: Int) = if(b==0) "" else String.format(Locale.US,"%.2f",100.0*a/b)
    private fun lower(w: Int,n: Int): String {
        if(n==0)return ""
        val p=w.toDouble()/n; val z=1.96; val d=1+z*z/n
        return String.format(Locale.US,"%.2f",100*(p+z*z/(2*n)-z*sqrt((p*(1-p)+z*z/(4*n))/n))/d)
    }
    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        val ordered=markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        // Freeze the signal definition before examining results:
        // prior completed reading 50..55, next completed reading >=60,
        // same side of target; exactly one minute between readings.
        // The first minute uses the pre-market target as its 50 anchor.
        val signals=ordered.mapNotNull { m ->
            val rows=m.minutes.sortedBy { it.minute }
            val first=rows.firstOrNull { r ->
                val previous=rows.firstOrNull { it.minute==r.minute-1 }
                val prior=if(previous==null && r.minute==1) 50.0 else
                    previous?.signedChartLevel ?: Double.NaN
                val now=r.signedChartLevel
                prior.isFinite() && now.isFinite() &&
                    kotlin.math.abs(prior) in 50.0..55.0 &&
                    kotlin.math.abs(now)>=60.0 && prior*now>0.0
            } ?: return@mapNotNull null
            val hour=Instant.ofEpochMilli(m.startMs).atZone(zone).hour
            val down=first.signedChartLevel>0
            Signal(m.startMs,hour,15-first.minute,down,if(down)!m.upWon else m.upWon)
        }
        val cut=ordered.size*70/100
        val splits=listOf("older_discovery" to ordered.take(cut),
            "newer_check" to ordered.drop(cut),"all_descriptive" to ordered)
        return buildString {
            appendLine("split,market_start_hour_ET,minutes_left,side,eligible_markets,alerts,hit_rate_pct,wins,losses,win_rate_pct,wilson95_lower_pct,first_market_ET,last_market_ET,notes")
            for((split,sample) in splits) {
                val starts=sample.map{it.startMs}.toHashSet()
                val subset=signals.filter{it.start in starts}
                val first=sample.firstOrNull()?.let{Instant.ofEpochMilli(it.startMs).atZone(zone).toString()}.orEmpty()
                val last=sample.lastOrNull()?.let{Instant.ofEpochMilli(it.startMs).atZone(zone).toString()}.orEmpty()
                for(hour in listOf(-1)+(10..22).toList()) {
                    for(left in listOf(-1)+(14 downTo 1).toList()) {
                        for(side in listOf("DOWN","UP","BOTH")) {
                            val eligible=sample.count { hour<0 || Instant.ofEpochMilli(it.startMs).atZone(zone).hour==hour }
                            val matches=subset.filter{ (hour<0||it.hour==hour) && (left<0||it.left==left) &&
                                (side=="BOTH"||(if(it.down)"DOWN" else "UP")==side) }
                            val wins=matches.count{it.won}
                            appendLine(listOf(split,if(hour<0)"ALL" else hour,if(left<0)"ALL" else left,
                                side,eligible,matches.size,pct(matches.size,eligible),wins,matches.size-wins,
                                pct(wins,matches.size),lower(wins,matches.size),first,last,
                                "closed_1m_50to55_to60plus;first_signal_per_market;BTC_proxy_not_Kalshi_odds").joinToString(","))
                        }
                    }
                }
            }
        }
    }
}
