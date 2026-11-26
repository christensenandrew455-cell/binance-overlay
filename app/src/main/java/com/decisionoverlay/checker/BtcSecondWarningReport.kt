package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/** Timestamped historical Binance trades; not actual Kalshi quotes or fills. */
internal object BtcSecondWarningReport {
    private const val M = 60_000L
    private val zone = ZoneId.of("America/New_York")
    data class Trade(val id: Long, val at: Long, val price: Double, val qty: Double)
    private data class Buy(val left: Int,val up: Boolean,val won: Boolean)
    private data class Warning(val left: Int,val up: Boolean,val won: Boolean,val seconds: Int)
    private data class Key(val seconds: Int,val threshold: Double,val left: Int,val hour: Int)
    private data class Count(var buys: Int=0,var coveredBuys: Int=0,var warnings: Int=0,
        var wins: Int=0,var confirmed: Int=0,var confirmedWins: Int=0)
    private data class Replay(val start: Long,val actual: Buy?,val covered: Boolean,
        val warnings: Map<Pair<Int,Double>,List<Warning>>)
    private val leads=listOf(15,30)
    private val thresholds=listOf(55.0,56.0,57.0,57.5,58.0,59.0,60.0)
    private fun hour(start: Long)=Instant.ofEpochMilli(start).atZone(zone).hour
    private fun fmt(n: Double)=String.format(Locale.US,"%.2f",n)
    private fun pct(n: Int,d: Int)=if(d==0) "" else fmt(100.0*n/d)

    /** Strict cutoff: trades at or after the deadline are never visible. */
    internal fun priceAt(trades: List<Trade>, cutoff: Long): Double? {
        val latest=trades.lastOrNull { it.at<cutoff } ?: return null
        return latest.price.takeIf { cutoff-latest.at<=15_000L && it.isFinite() && it>0 }
    }

    private fun replay(m: BtcReversalTimingReport.Market, raw: List<Trade>?): Replay {
        val start=m.base.startMs
        val rows=m.rows.sortedBy { it.minute }
        val history=mutableListOf<BtcChartProxyMath.Sample>()
        var actual: Buy?=null
        for(row in rows) {
            history.add(BtcChartProxyMath.Sample(15-row.minute,row.level))
            val s=KalshiLiveSignalRules.matchingSignedSignals(history).firstOrNull() ?: continue
            actual=Buy(s.left,s.rule.buyUp,s.rule.buyUp==m.base.upWon)
            break
        }
        val trades=raw?.sortedWith(compareBy<Trade>{it.at}.thenBy{it.id})
        // Compare aggregate trade totals and last trade with each source candle.
        // Missing or truncated data must never be treated as a failed warning.
        val covered=trades!=null && rows.size==14 && rows.all { row ->
            val open=start+(row.minute-1L)*M
            val minuteTrades=trades.filter { it.at>=open && it.at<open+M }
            val qty=minuteTrades.sumOf { it.qty }
            row.candleVolume.isFinite() && row.candleVolume>=0 &&
                abs(qty-row.candleVolume)<=maxOf(0.0000001,row.candleVolume*0.00001) &&
                (minuteTrades.isEmpty() && row.candleVolume==0.0 ||
                 minuteTrades.lastOrNull()?.let { abs(it.price-row.close)<=0.000001 }==true) &&
                row.priorAcm.isFinite() && row.priorAcm>0
        }
        if(!covered || trades==null) return Replay(start,actual,false,emptyMap())
        val observed=mutableMapOf<Int,Double>()
        val warnings=mutableMapOf<Pair<Int,Double>,MutableList<Warning>>()
        for(row in rows) {
            val left=15-row.minute
            val closeAt=start+row.minute*M
            for(seconds in leads) {
                val cutoff=closeAt-seconds*1000L
                val price=priceAt(trades,cutoff) ?: continue
                val level=BtcChartProxyMath.signedStrength(price,m.base.targetBtc,
                    row.priorAcm,left+seconds/60.0)
                if(!level.isFinite() || level==0.0 || abs(level)>=70.0) continue
                val up=level>0
                val hasStart=KalshiLiveSignalRules.rules.any { rule ->
                    val from=left+rule.gapMinutes
                    rule.buyUp==up && from in 1..14 &&
                        BtcExhaustivePatternReport.band(observed[from] ?: Double.NaN)==rule.fromBand &&
                        KalshiLiveSignalRules.windowFor(rule,from,left)?.eligible==true &&
                        observed.none { (prior,priorLevel) ->
                            prior>left && BtcExhaustivePatternReport.band(priorLevel)==rule.toBand &&
                                BtcExhaustivePatternReport.band(observed[prior+rule.gapMinutes] ?: Double.NaN)==rule.fromBand &&
                                KalshiLiveSignalRules.windowFor(rule,prior+rule.gapMinutes,prior)?.eligible==true
                        }
                }
                if(!hasStart) continue
                for(threshold in thresholds) {
                    if(abs(level)<threshold) continue
                    warnings.getOrPut(seconds to threshold){mutableListOf()}
                        .add(Warning(left,up,up==m.base.upWon,seconds))
                }
            }
            observed[left]=row.level
        }
        return Replay(start,actual,true,warnings)
    }

    /** Existing 31-column reversal CSV schema. Warning lead is expressed in minutes. */
    fun report(input: List<BtcReversalTimingReport.Market>,
        fetch: (Long,Long)->List<Trade>?, progress: (Int,Int)->Unit): String {
        val ordered=input.distinctBy { it.base.startMs }.sortedBy { it.base.startMs }
        val results=ordered.mapIndexed { i,m ->
            val start=m.base.startMs
            val result=replay(m,fetch(start,start+14*M-1))
            progress(i+1,ordered.size)
            result
        }
        val cut=(results.size*0.7).toInt()
        val older=results.take(cut)
        val newer=results.drop(cut)
        val olderCells=older.mapNotNull { r -> r.actual?.let { (hour(r.start) to it.left) to it } }
            .groupBy({it.first},{it.second})
        val keep=olderCells.filterValues { it.isNotEmpty() && 1000L*it.count { b -> b.won }>=795L*it.size }.keys
        val keep10=olderCells.filterValues { it.size>=10 && 1000L*it.count { b -> b.won }>=795L*it.size }.keys
        val allCells=results.mapNotNull { r -> r.actual?.let { (hour(r.start) to it.left) to it } }
            .groupBy({it.first},{it.second})
        val allKeep=allCells.filterValues { 1000L*it.count { b -> b.won }>=795L*it.size }.keys
        data class Sample(val label: String,val rows: List<Replay>,val filter: Set<Pair<Int,Int>>?)
        val samples=listOf(Sample("older_discovery",older,null),Sample("newer_check",newer,null),
            Sample("all_descriptive",results,null),Sample("all_rounded80_in_sample",results,allKeep),
            Sample("older_rounded80_discovery",older,keep),Sample("newer_older80",newer,keep),
            Sample("newer_older80_min10",newer,keep10))
        return buildString {
            for(sample in samples) {
                val first=sample.rows.firstOrNull()?.let { Instant.ofEpochMilli(it.start).atZone(zone).toString() }.orEmpty()
                val last=sample.rows.lastOrNull()?.let { Instant.ofEpochMilli(it.start).atZone(zone).toString() }.orEmpty()
                val groups=sample.rows.groupBy { hour(it.start) }
                for(h in listOf(-1)+(10..22).toList()) {
                    val group=if(h<0)sample.rows else groups[h].orEmpty()
                    val n=group.size
                    val good=group.count { it.covered }
                    // hits=verified trade-data markets; losses=unverified markets.
                    appendLine(listOf(sample.label,"second_data_coverage","BOTH","trade_candle_reconciliation",
                        "ALL",if(h<0)"ALL" else h,n,good,pct(good,n),"",n-good,"","","","","",
                        first,last,"","","","","","","","","","","","","").joinToString(","))
                }
                val counts=mutableMapOf<Key,Count>()
                fun keys(s: Int,t: Double,l: Int,h: Int)=listOf(Key(s,t,0,-1),Key(s,t,l,-1),
                    Key(s,t,0,h),Key(s,t,l,h))
                for(r in sample.rows) {
                    val h=hour(r.start)
                    val a=r.actual
                    val eligible=a!=null && (sample.filter==null || (h to a.left) in sample.filter)
                    for(s in leads) for(t in thresholds) {
                        if(a!=null && eligible) for(k in keys(s,t,a.left,h)) {
                            val c=counts.getOrPut(k){Count()}
                            c.buys++
                            if(r.covered)c.coveredBuys++
                        }
                        // First warning per market, with filter applied before selecting it.
                        val w=r.warnings[s to t].orEmpty().firstOrNull {
                            (a==null || it.left>=a.left) && (sample.filter==null || (h to it.left) in sample.filter)
                        } ?: continue
                        val confirmed=eligible && a!=null && a.left==w.left && a.up==w.up
                        for(k in keys(s,t,w.left,h)) {
                            val c=counts.getOrPut(k){Count()}
                            c.warnings++
                            if(w.won)c.wins++
                            if(confirmed){c.confirmed++; if(w.won)c.confirmedWins++}
                        }
                    }
                }
                for(s in leads) for(t in thresholds) {
                    val output=linkedSetOf(Key(s,t,0,-1))
                    output.addAll((14 downTo 1).map { Key(s,t,it,-1) })
                    output.addAll((10..22).map { Key(s,t,0,it) })
                    output.addAll(counts.keys.filter { it.seconds==s && it.threshold==t && it.left>0 && it.hour>=0 }
                        .sortedWith(compareBy<Key>{it.hour}.thenByDescending{it.left}))
                    for(k in output) {
                        val c=counts[k] ?: Count()
                        val group=if(k.hour<0)sample.rows else groups[k.hour].orEmpty()
                        val n=group.count { it.covered }
                        // Coverage denominator includes ALL eligible buys, including missing data.
                        // stop_reason records the number of eligible buys with verified data.
                        appendLine(listOf(sample.label,"early_warning_seconds","BOTH","threshold_"+fmt(t),
                            if(k.left==0)"ALL" else k.left,if(k.hour<0)"ALL" else k.hour,
                            n,c.warnings,pct(c.warnings,n),c.wins,c.warnings-c.wins,pct(c.wins,c.warnings),
                            "","","","",first,last,"","","","","covered_buys="+c.coveredBuys,
                            if(s==15)"0.25" else "0.50",c.buys,c.confirmed,c.warnings-c.confirmed,
                            pct(c.confirmed,c.warnings),pct(c.confirmed,c.buys),
                            c.confirmedWins,pct(c.confirmedWins,c.confirmed)).joinToString(","))
                    }
                }
                // Direct entry: one candidate per market, no subsequent confirmation required.
                // A skipped confirmed BUY does not stop the direct strategy from finding a later
                // eligible setup. Both cutoff choices are individually tested, plus earliest of either.
                data class DirectCount(var entries: Int=0,var wins: Int=0,var exactMatches: Int=0,
                    var matchedWins: Int=0,var lead15: Int=0,var lead30: Int=0)
                val verified=sample.rows.filter { it.covered }
                for (mode in listOf(15,30,0)) for(t in thresholds) {
                    val cells=mutableMapOf<Pair<Int,Int>,DirectCount>()
                    val days=mutableMapOf<String,DirectCount>()
                    val dates=verified.groupBy { Instant.ofEpochMilli(it.start).atZone(zone).toLocalDate().toString() }
                    for(r in verified) {
                        val h=hour(r.start)
                        val candidates=(if(mode==0) leads else listOf(mode))
                            .flatMap { r.warnings[it to t].orEmpty() }
                            .filter { sample.filter==null || (h to it.left) in sample.filter }
                        // More minutes left is earlier; at the same minute 30s is earlier than 15s.
                        val w=candidates.sortedWith(compareByDescending<Warning>{it.left}
                            .thenByDescending{it.seconds}).firstOrNull() ?: continue
                        val a=r.actual
                        val exact=a!=null && a.left==w.left && a.up==w.up
                        fun add(c: DirectCount) {
                            c.entries++
                            if(w.won)c.wins++
                            if(exact){c.exactMatches++; if(w.won)c.matchedWins++}
                            if(w.seconds==15)c.lead15++ else c.lead30++
                        }
                        for(k in listOf(0 to -1,w.left to -1,0 to h,w.left to h)) {
                            add(cells.getOrPut(k){DirectCount()})
                        }
                        val date=Instant.ofEpochMilli(r.start).atZone(zone).toLocalDate().toString()
                        add(days.getOrPut(date){DirectCount()})
                    }
                    val setup=(if(mode==0) "first_15_or_30s_" else mode.toString()+"s_")+"threshold_"+fmt(t)
                    fun emit(c: DirectCount,n: Int,left: Any,hour: Any,label: String,
                        firstTime: String,lastTime: String,baselineBuys: Int) {
                        appendLine(listOf(sample.label,label,"BOTH",setup,left,hour,n,
                            c.entries,pct(c.entries,n),c.wins,c.entries-c.wins,pct(c.wins,c.entries),
                            "","","","",firstTime,lastTime,"","","","",
                            "lead15="+c.lead15+";lead30="+c.lead30,
                            if(mode==0)"" else if(mode==15)"0.25" else "0.50",
                            baselineBuys,c.exactMatches,c.entries-c.exactMatches,
                            pct(c.exactMatches,c.entries),pct(c.exactMatches,baselineBuys),
                            c.matchedWins,pct(c.matchedWins,c.exactMatches)).joinToString(","))
                    }
                    val output=linkedSetOf(0 to -1)
                    output.addAll((14 downTo 1).map { it to -1 })
                    output.addAll((10..22).map { 0 to it })
                    output.addAll(cells.keys.filter { it.first>0 && it.second>=0 }
                        .sortedWith(compareBy<Pair<Int,Int>>{it.second}.thenByDescending{it.first}))
                    for((left,h) in output) {
                        val group=if(h<0)verified else verified.filter { hour(it.start)==h }
                        val baseline=group.count { r ->
                            val a=r.actual
                            a!=null && (left==0 || a.left==left) &&
                                (sample.filter==null || (hour(r.start) to a.left) in sample.filter)
                        }
                        emit(cells[left to h] ?: DirectCount(),group.size,
                            if(left==0)"ALL" else left,if(h<0)"ALL" else h,"near_buy_direct",
                            first,last,baseline)
                    }
                    for((date,group) in dates) {
                        val baseline=group.count { r -> r.actual?.let {
                            sample.filter==null || (hour(r.start) to it.left) in sample.filter
                        }==true }
                        emit(days[date] ?: DirectCount(),group.size,"ALL","ALL","near_buy_day_"+date,
                            Instant.ofEpochMilli(group.first().start).atZone(zone).toString(),
                            Instant.ofEpochMilli(group.last().start).atZone(zone).toString(),baseline)
                    }
                }
                // Matched verified-data baseline, for a like-for-like comparison.
                val baseline=verified.filter { r -> r.actual?.let {
                    sample.filter==null || (hour(r.start) to it.left) in sample.filter
                }==true }
                val wins=baseline.count { it.actual?.won==true }
                appendLine(listOf(sample.label,"confirmed_buy_baseline","BOTH","first_buy_verified_markets",
                    "ALL","ALL",verified.size,baseline.size,pct(baseline.size,verified.size),
                    wins,baseline.size-wins,pct(wins,baseline.size),"","","","",first,last,
                    "","","","","","","","","","","","","").joinToString(","))
            }
        }
    }
}
