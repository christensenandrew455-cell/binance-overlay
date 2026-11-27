package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/** Research only. Slope at market OPEN; no current-market candles enter the EMA. */
internal object BtcEmaSlopeReport {
    private const val M = 60_000L
    const val WARMUP_MINUTES = 15L * (500 + 15)
    private val zone = ZoneId.of("America/New_York")
    data class Config(val timeframe: Int, val period: Int, val lookback: Int) {
        val label get() = "tf=${timeframe}m;ema=$period;lookback=$lookback"
    }
    val configs = (listOf(1, 5, 15)).flatMap { tf ->
        (5..100 step 5).flatMap { p -> (1..15).map { k -> Config(tf,p,k) } }
    }
    /** Keyed by close boundary, not open time. An incomplete block resets EMA history. */
    internal fun ema(candles: Map<Long,BtcAcmMath.Candle>, tf: Int, period: Int): Map<Long,Double> {
        if (candles.isEmpty()) return emptyMap()
        val step = tf*M
        val min = candles.keys.minOrNull()!!
        val max = candles.keys.maxOrNull()!!
        val first = (min / step + if (min % step == 0L) 0 else 1) * step
        val result = HashMap<Long,Double>()
        val alpha = 2.0 / (period+1)
        var count = 0
        var sum = 0.0
        var value = 0.0
        for (open in first..max step step) {
            val block = (0 until tf).map { candles[open+it*M] }
            if (block.any { it == null || !it.valid() }) {
                count=0; sum=0.0; value=0.0
                continue
            }
            val close = block.last()!!.close
            count++
            if (count <= period) {
                sum += close
                if (count == period) value = sum/period
            } else value += alpha*(close-value)
            // Burn-in reduces dependence on initialization; missing blocks restart it.
            if (count >= 5*period) result[open+step] = value
        }
        return result
    }
    internal fun slope(values: Map<Long,Double>, start: Long, c: Config): Double? {
        val step = c.timeframe*M
        val boundary = start / step * step
        val current = values[boundary] ?: return null
        val previous = values[boundary-c.lookback*step] ?: return null
        if (previous <= 0.0) return null
        return (100.0*(current/previous-1.0)/(c.timeframe*c.lookback)).takeIf { it.isFinite() }
    }
    private data class Count(var up: Int=0,var down: Int=0,var upWins: Int=0,
        var downWins: Int=0,var flat: Int=0,var missing: Int=0) {
        val hits get() = up+down
        val wins get() = upWins+downWins
        val accuracy get() = if(hits==0) -1.0 else wins.toDouble()/hits
        fun add(slope: Double?, actualUp: Boolean) {
            when {
                slope==null -> missing++
                slope==0.0 -> flat++
                slope>0 -> { up++; if(actualUp) upWins++ }
                else -> { down++; if(!actualUp) downWins++ }
            }
        }
    }
    private data class Score(val config: Config,val older: Count,val newer: Count,val all: Count)
    private fun pct(n: Int,d: Int) = if(d==0) "" else String.format(Locale.US,"%.4f",100.0*n/d)

    /** Return rows in the existing reversal CSV schema (31 columns, no second header). */
    fun report(input: List<BinanceTwentySevenPatternReport.Market>,
        candles: Map<Long,BtcAcmMath.Candle>): String {
        val markets = input.distinctBy { it.startMs }.sortedBy { it.startMs }
        val cut = (markets.size*0.7).toInt()
        val scores = mutableListOf<Score>()
        for ((pair, group) in configs.groupBy { it.timeframe to it.period }) {
            val values = ema(candles,pair.first,pair.second)
            for (c in group) {
                val older=Count(); val newer=Count(); val all=Count()
                for ((i,m) in markets.withIndex()) {
                    val s=slope(values,m.startMs,c)
                    all.add(s,m.upWon)
                    (if(i<cut) older else newer).add(s,m.upWon)
                }
                scores.add(Score(c,older,newer,all))
            }
        }
        // Only full-coverage discovery candidates can be selected. No holdout-based selection.
        val eligible = scores.filter { cut>0 && it.older.hits==cut }
        val ranking = compareByDescending<Score>{it.older.accuracy}
            .thenByDescending{it.older.hits}.thenBy{it.config.timeframe}
            .thenBy{it.config.period}.thenBy{it.config.lookback}
        val ordered = scores.sortedWith(ranking)
        val winner = eligible.sortedWith(ranking).firstOrNull()
        val allRank = scores.sortedWith(compareByDescending<Score>{it.all.accuracy}
            .thenByDescending{it.all.hits}.thenBy{it.config.timeframe}
            .thenBy{it.config.period}.thenBy{it.config.lookback})
        return buildString {
            fun row(split: String, family: String, side: String, setup: String,
                sample: List<BinanceTwentySevenPatternReport.Market>, hits: Int,wins: Int,notes: String) {
                val fields = MutableList(31){""}
                fields[0]=split; fields[1]=family; fields[2]=side; fields[3]=setup
                fields[4]="15"; fields[5]="ALL"; fields[6]=sample.size.toString()
                fields[7]=hits.toString(); fields[8]=pct(hits,sample.size)
                fields[9]=wins.toString(); fields[10]=(hits-wins).toString()
                fields[11]=pct(wins,hits)
                fields[16]=sample.firstOrNull()?.let{Instant.ofEpochMilli(it.startMs).atZone(zone).toString()}.orEmpty()
                fields[17]=sample.lastOrNull()?.let{Instant.ofEpochMilli(it.startMs).atZone(zone).toString()}.orEmpty()
                fields[22]=notes
                appendLine(fields.joinToString(","))
            }
            fun emit(split: String,family: String,score: Score,c: Count,
                sample: List<BinanceTwentySevenPatternReport.Market>,rank: Int) {
                val notes="rank=$rank;flat=${c.flat};missing=${c.missing};open_only;binance_proxy"
                row(split,family,"BOTH",score.config.label,sample,c.hits,c.wins,notes)
                row(split,family,"UP",score.config.label,sample,c.up,c.upWins,notes)
                row(split,family,"DOWN",score.config.label,sample,c.down,c.downWins,notes)
            }
            val older=markets.take(cut); val newer=markets.drop(cut)
            if(winner!=null) {
                emit("older_discovery","ema_slope_selected",winner,winner.older,older,1)
                emit("newer_check","ema_slope_selected",winner,winner.newer,newer,1)
            } else row("older_discovery","ema_slope_selected","NONE","no_full_coverage_candidate",
                older,0,0,"No configuration has a directional prediction for every discovery market")
            for ((i,s) in ordered.withIndex()) {
                emit("older_discovery","ema_slope_grid",s,s.older,older,i+1)
                emit("newer_check","ema_slope_grid",s,s.newer,newer,i+1)
            }
            for ((i,s) in allRank.withIndex())
                emit("all_descriptive","ema_slope_grid",s,s.all,markets,i+1)
            // Majority baseline learned only on older markets.
            val majorityUp = older.count{it.upWon}*2 >= older.size
            row("newer_check","ema_slope_baseline",if(majorityUp)"UP" else "DOWN",
                "discovery_majority",newer,newer.size,newer.count{it.upWon==majorityUp},"no_ema")
        }
    }
    /** Fixed configuration; only completed native 5m candles at each observation. */
    fun timingReport(input: List<BinanceTwentySevenPatternReport.Market>,
        candles: Map<Long,BtcAcmMath.Candle>): String {
        val markets = input.distinctBy { it.startMs }.sortedBy { it.startMs }
        val c = Config(5,25,12)
        val values = ema(candles,c.timeframe,c.period)
        val cut = (markets.size*0.7).toInt()
        return buildString {
            for (left in 15 downTo 1) {
                for ((split,sample) in listOf("all" to markets,
                    "older_discovery" to markets.take(cut), "newer_check" to markets.drop(cut))) {
                    val count = Count()
                    for (m in sample) count.add(slope(values,m.startMs+(15-left)*M,c),m.upWon)
                    for ((side,hits,wins) in listOf(Triple("BOTH",count.hits,count.wins),
                        Triple("UP",count.up,count.upWins),Triple("DOWN",count.down,count.downWins))) {
                        val f = MutableList(31){""}
                        f[0]=split; f[1]="ema_slope_timing"; f[2]=side; f[3]=c.label
                        f[4]=left.toString(); f[5]=(15-left).toString(); f[6]=sample.size.toString()
                        f[7]=hits.toString(); f[8]=pct(hits,sample.size)
                        f[9]=wins.toString(); f[10]=(hits-wins).toString(); f[11]=pct(wins,hits)
                        f[22]="minutes_left=$left;flat=${count.flat};missing=${count.missing};closed_5m_only"
                        appendLine(f.joinToString(","))
                    }
                }
            }
        }
    }

    /** Freeze the forecast at each checkpoint; never use future candles to set its bounds. */
    fun rangeReport(input: List<BinanceTwentySevenPatternReport.Market>,
        candles: Map<Long,BtcAcmMath.Candle>): String {
        val markets = input.distinctBy { it.startMs }.sortedBy { it.startMs }
        val c = Config(5,25,12)
        val values = ema(candles,c.timeframe,c.period)
        val cut = (markets.size*0.7).toInt()
        return buildString {
            for (left in 15 downTo 1) {
                for (band in listOf(60,70,80,90)) {
                    val fraction = (band-50)/40.0
                    for ((split,sample) in listOf("all" to markets,
                        "older_discovery" to markets.take(cut), "newer_check" to markets.drop(cut))) {
                        var valid = 0
                        var settle = 0
                        var stay = 0
                        var above = 0
                        var below = 0
                        var widthSum = 0.0
                        for (m in sample) {
                            val at = m.startMs+(15-left)*M
                            val anchor = candles[at-M]?.takeIf { it.valid() }?.close ?: continue
                            val rate = slope(values,at,c) ?: continue
                            val acm = BtcAcmMath.averageRange(candles,at-M,60) ?: continue
                            if (acm <= 0.0) continue
                            val future = (0 until left).map { candles[at+it*M] }
                            if (future.any { it == null || !it.valid() }) continue
                            fun center(t: Int) = anchor*(1.0+rate*t/100.0)
                            fun width(t: Int) = anchor*acm*fraction*t/100.0
                            val lower = center(left)-width(left)
                            val upper = center(left)+width(left)
                            val final = future.last()!!.close
                            valid++
                            widthSum += width(left)/anchor*100.0
                            if (final in lower..upper) settle++
                            else if (final > upper) above++ else below++
                            // A widening fan: test each minute's entire high/low against
                            // the envelope swept during that minute, not just its close.
                            if (future.withIndex().all { (i,bar) ->
                                val lo = minOf(center(i)-width(i),center(i+1)-width(i+1))
                                val hi = maxOf(center(i)+width(i),center(i+1)+width(i+1))
                                bar!!.low >= lo && bar.high <= hi
                            }) stay++
                        }
                        val f = MutableList(31){""}
                        f[0]=split; f[1]="ema_projected_range"; f[2]="±$band"; f[3]=c.label
                        f[4]=left.toString(); f[5]=(15-left).toString(); f[6]=sample.size.toString()
                        f[7]=valid.toString(); f[8]=pct(valid,sample.size)
                        f[9]=settle.toString(); f[10]=(valid-settle).toString(); f[11]=pct(settle,valid)
                        f[12]=stay.toString(); f[13]=pct(stay,valid)
                        f[14]=above.toString(); f[15]=below.toString()
                        f[18]=(sample.size-valid).toString()
                        f[19]=if(valid==0) "" else String.format(Locale.US,"%.6f",widthSum/valid)
                        f[22]="frozen_forecast;closed_5m_slope;acm60;minute_swept_envelope;not_kalshi_odds"
                        appendLine(f.joinToString(","))
                    }
                }
            }
        }
    }

    /** Range split versus the actual target; all observations are frozen at the checkpoint. */
    fun splitReport(input: List<BinanceTwentySevenPatternReport.Market>,
        candles: Map<Long,BtcAcmMath.Candle>): String {
        val markets = input.distinctBy { it.startMs }.sortedBy { it.startMs }
        val cut = (markets.size * 0.7).toInt()
        val config = Config(5,25,12)
        val emaValues = ema(candles,5,25)
        val result = StringBuilder()
        result.appendLine("split,minutes_left,band,majority_side,majority_bucket,markets,valid,correct,accuracy_pct,mean_majority_share_pct,actual_up,actual_down,inside_range,inside_pct,notes")
        for (left in 15 downTo 1) for (band in listOf(60,70,80,90)) {
            val fraction = (band-50)/40.0
            data class Sample(val majority: String,val bucket: Int,val share: Double,
                val correct: Boolean,val up: Boolean,val inside: Boolean)
            val observations = markets.map { market ->
                val at = market.startMs + (15-left)*M
                val anchor = candles[at-M]?.takeIf { it.valid() }?.close
                val rate = slope(emaValues,at,config)
                val acm = BtcAcmMath.averageRange(candles,at-M,60)
                val final = candles[market.startMs+14*M]?.takeIf { it.valid() }?.close
                if (anchor==null || rate==null || acm==null || acm<=0 || final==null) null
                else {
                    val center = anchor*(1.0+rate*left/100.0)
                    val half = anchor*acm*fraction*left/100.0
                    val lo = center-half
                    val hi = center+half
                    val target = market.targetBtc
                    val above = ((hi-maxOf(lo,target))/(hi-lo)).coerceIn(0.0,1.0)
                    val majority = if (above>=0.5) "UP" else "DOWN"
                    val share = maxOf(above,1.0-above)
                    val bucket = minOf(90, ((share*100.0).toInt()/10)*10)
                    Sample(majority,bucket,share, (final>target)==(majority=="UP"),
                        final>target,final in lo..hi)
                }
            }
            for ((split, indices) in listOf("all" to markets.indices,
                "older_discovery" to (0 until cut),
                "newer_check" to (cut until markets.size))) {
                val valid = indices.mapNotNull { observations[it] }
                for (side in listOf("UP","DOWN","BOTH")) for (bucket in listOf(50,60,70,80,90, -1)) {
                    val subset = valid.filter {
                        (side=="BOTH" || it.majority==side) && (bucket==-1 || it.bucket==bucket)
                    }
                    if (subset.isEmpty()) continue
                    val correct = subset.count { it.correct }
                    val up = subset.count { it.up }
                    val inside = subset.count { it.inside }
                    val meanShare = subset.map { it.share }.average()*100.0
                    val fmt = { v: Double -> String.format(Locale.US,"%.4f",v) }
                    result.appendLine(listOf(split,left,band,side,
                        if(bucket==-1) "ALL" else "${bucket}-${if(bucket==90)100 else bucket+10}",
                        markets.size,subset.size,correct,fmt(correct*100.0/subset.size),
                        fmt(meanShare),up,subset.size-up,inside,fmt(inside*100.0/subset.size),
                        "fixed_ema_5m_25_12;acm60;geometric_share_not_probability")
                        .joinToString(","))
                }
            }
        }
        return result.toString()
    }

}
