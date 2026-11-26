package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** Exploratory report only; no live orders or modifications to alert rules. */
internal object BtcReversalTimingReport {
    private const val M = 60_000L
    private val zone = ZoneId.of("America/New_York")
    private val bands = listOf(50, 60, 70, 80, 90)
    data class Row(val minute: Int, val level: Double, val momentum1: Double,
        val momentum3: Double, val acceleration: Double, val trend15: Double, val volumeRatio: Double)
    data class Market(val base: BinanceTwentySevenPatternReport.Market, val rows: List<Row>)

    fun market(base: BinanceTwentySevenPatternReport.Market, candles: Map<Long,BtcAcmMath.Candle>): Market {
        val rows = base.minutes.map { obs ->
            val t = base.startMs + (obs.minute - 1L)*M
            fun close(back: Int) = candles.getValue(t-back*M).close
            fun change(a: Double,b: Double) = 100*(a/b-1)
            val one = change(close(0),close(1))
            val previous = change(close(1),close(2))
            val volumes = (1..59).map { candles.getValue(t-it*M).volume }
            val v = candles.getValue(t).volume
            val avg = volumes.average()
            val ratio = if (v.isFinite() && v >= 0 && volumes.all { it.isFinite() && it >= 0 } && avg > 0) v/avg else Double.NaN
            Row(obs.minute,obs.signedChartLevel,one,change(close(0),close(3)),one-previous,
                change(close(0),close(15)),ratio)
        }
        return Market(base,rows)
    }
    private data class Key(val family: String,val side: String,val setup: String,val left: Int,val hour: Int)
    private data class Count(var hits: Int=0,var wins: Int=0,var crosses: Int=0,var follow: Int=0)
    private fun band(level: Double): Int = if (level == 0.0) 0 else
        (abs(level)/10).toInt().coerceIn(5,9)*10
    private fun pct(n: Int,d: Int) = if (d == 0) "" else fmt(100.0*n/d)
    private fun fmt(n: Double) = String.format(Locale.US,"%.2f",n)
    private fun lower(w: Int,n: Int): String {
        if(n==0) return ""
        val p=w.toDouble()/n; val z=1.96; val z2=z*z
        return fmt(100*(p+z2/(2*n)-z*sqrt((p*(1-p)+z2/(4*n))/n))/(1+z2/n))
    }
    private fun direction(value: Double,side: Int) = when {
        value*side<0 -> "opposite"
        value*side>0 -> "same"
        else -> "flat"
    }
    private fun volume(v: Double) = when {
        !v.isFinite() -> "missing"
        v<0.75 -> "low"
        v<1.5 -> "normal"
        else -> "high"
    }
    fun report(input: List<Market>): String {
        val markets=input.distinctBy { it.base.startMs }.sortedBy { it.base.startMs }
        val cut=(markets.size*0.7).toInt()
        val splits=listOf("older_discovery" to markets.take(cut),"newer_check" to markets.drop(cut),"all_descriptive" to markets)
        val keys=linkedSetOf<Key>()
        val results=mutableMapOf<String,Map<Key,Count>>()
        for ((split, sample) in splits) {
            val counts=mutableMapOf<Key,Count>()
            for (m in sample) {
                val hour=Instant.ofEpochMilli(m.base.startMs).atZone(zone).hour
                val seen=mutableSetOf<Key>()
                val history=mutableListOf<BtcChartProxyMath.Sample>()
                val peaks=mutableMapOf<Int,Int>()
                var existing=false
                for (r in m.rows.sortedBy { it.minute }) {
                    val left=15-r.minute
                    val side=if(r.level>0) 1 else if(r.level<0) -1 else 0
                    fun emit(family: String, name: String, setup: String, buyUp: Boolean, followUp: Boolean=false) {
                        val won=buyUp==m.base.upWon
                        // A subsequent candle touch is not the same as a winning settlement.
                        val crossed=m.base.minutes.any { o -> o.minute>r.minute &&
                            if(buyUp) o.high>m.base.targetBtc else o.low<m.base.targetBtc } || won
                        for ((l,h) in listOf(0 to -1,left to -1,0 to hour,left to hour)) {
                            val key=Key(family,name,setup,l,h)
                            if(!seen.add(key)) continue
                            val c=counts.getOrPut(key){Count()}
                            c.hits++; if(won)c.wins++; if(crossed)c.crosses++; if(followUp)c.follow++
                        }
                    }
                    history.add(BtcChartProxyMath.Sample(left,r.level))
                    if(!existing) {
                        val signal=KalshiLiveSignalRules.matchingSignedSignals(history).firstOrNull()
                        if(signal!=null) {
                            emit("existing_first_alert",if(signal.rule.buyUp) "UP" else "DOWN","frozen_buy",signal.rule.buyUp)
                            existing=true
                        }
                    }
                    if(side==0) { peaks.clear(); continue }
                    val b=band(r.level)
                    val name=if(side>0) "ABOVE_buy_DOWN" else "BELOW_buy_UP"
                    val buyUp=side<0
                    val priorPeak=peaks[side] ?: 0
                    val mom1=direction(r.momentum1,side)
                    val mom3=direction(r.momentum3,side)
                    val trend=direction(r.trend15,side)
                    val accel=direction(r.acceleration,side)
                    val vol=volume(r.volumeRatio)
                    val follow=m.rows.any { it.minute>r.minute && it.level*side<0 }
                    emit("band_reversal",name,"band_"+b,buyUp,follow)
                    val indicators=listOf("mom1="+mom1,"mom3="+mom3,"trend15="+trend,
                        "accel="+accel,"volume="+vol,
                        "mom1="+mom1+";volume="+vol,
                        "mom3="+mom3+";trend15="+trend,
                        "mom1="+mom1+";mom3="+mom3+";trend15="+trend+";volume="+vol)
                    for (indicator in indicators) emit("indicator_reversal",name,"band_"+b+";"+indicator,buyUp,follow)
                    for (peak in bands.filter { it>b && it<=priorPeak }) {
                        emit("same_side_pullback",name,peak.toString()+"_to_"+b,buyUp,follow)
                        for(indicator in indicators.take(5)) emit("pullback_indicator",name,peak.toString()+"_to_"+b+";"+indicator,buyUp,follow)
                    }
                    peaks[-side]=0
                    peaks[side]=maxOf(priorPeak,b)
                }
            }
            keys.addAll(counts.keys)
            results[split]=counts
        }
        // Include zero-match cells for each observed setup and each clock/minute group.
        // Overall counts use the first match per market; exact-time rows use the first match within that cell.
        val setups=keys.map { Triple(it.family,it.side,it.setup) }.distinct().sortedWith(
            compareBy<Triple<String,String,String>>{it.first}.thenBy{it.second}.thenBy{it.third})
        return buildString {
            appendLine("split,family,side,setup,minutes_left_or_ALL,market_start_hour_ET_or_ALL,eligible_markets,hits,hit_rate_pct,settlement_wins,settlement_losses,win_rate_pct,wilson95_lower_pct,later_target_cross_or_win,subsequent_opposite_close,opposite_close_pct,first_market_ET,last_market_ET")
            for ((split,sample) in splits) {
                val map=results.getValue(split)
                val hourCounts=sample.groupingBy { Instant.ofEpochMilli(it.base.startMs).atZone(zone).hour }.eachCount()
                val first=sample.firstOrNull()?.let { Instant.ofEpochMilli(it.base.startMs).atZone(zone).toString() }.orEmpty()
                val last=sample.lastOrNull()?.let { Instant.ofEpochMilli(it.base.startMs).atZone(zone).toString() }.orEmpty()
                for ((family,side,setup) in setups) {
                    // Report marginal clock and minute breakdowns, plus observed joint cells only.
                    val cells=mutableListOf(0 to -1)
                    cells.addAll((14 downTo 1).map { it to -1 })
                    cells.addAll((10..22).map { 0 to it })
                    cells.addAll(keys.filter { it.family==family && it.side==side && it.setup==setup && it.left>0 && it.hour>=0 }.map { it.left to it.hour })
                    for ((left,hour) in cells.distinct()) {
                        val c=map[Key(family,side,setup,left,hour)] ?: Count()
                        val n=if(hour<0) sample.size else hourCounts[hour] ?: 0
                        appendLine(listOf(split,family,side,setup,if(left==0)"ALL" else left,if(hour<0)"ALL" else hour,
                            n,c.hits,pct(c.hits,n),c.wins,c.hits-c.wins,pct(c.wins,c.hits),lower(c.wins,c.hits),
                            c.crosses,if(family=="existing_first_alert") "" else c.follow,
                            if(family=="existing_first_alert") "" else pct(c.follow,c.hits),first,last).joinToString(","))
                    }
                }
            }
        }
    }
}
