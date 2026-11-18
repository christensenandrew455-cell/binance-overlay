package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Historical BTC OHLC empirical finish probabilities.
 * These are BINANCE.US proxy outcomes, not Kalshi settled-contract odds.
 * One observation per market per time bucket; no lookahead.
 * 30 complete days: older 20 days for training, newer 10 for holdout.
 * Do not alert unless independent older/newer market groups both support it.
 */
object BtcAcmOdds {
    val RATIOS = doubleArrayOf(0.0,0.15,0.25,0.35,0.50,0.65,0.80,
        1.00,1.25,1.50,2.00,2.50,3.00,4.00,5.00,7.00)
    const val MIN_TRAIN_MARKETS = 70
    const val MIN_TEST_MARKETS = 35
    const val EXTRA_EDGE = 0.02
    data class Counter(var n: Int = 0, var wins: Int = 0) {
        fun add(win: Boolean) { n++; if (win) wins++ }
        val rate get() = if (n > 0) wins.toDouble()/n else Double.NaN
        /** One-sided 95% Wilson lower confidence bound, z = 1.645 */
        val lower: Double get() {
            if (n == 0) return Double.NaN
            val z = 1.645
            val p = wins.toDouble()/n
            val denom = 1+z*z/n
            return ((p+z*z/(2*n))-z*sqrt((p*(1-p)+z*z/(4*n))/n))/denom
        }
    }
    data class Evidence(
        val minute: Int, val side: Int, val ratio: Double,
        val train: Counter, val holdout: Counter
    ) {
        val valid get() = train.n >= MIN_TRAIN_MARKETS &&
            holdout.n >= MIN_TEST_MARKETS
        val conservative get() = if (valid) minOf(train.lower,holdout.lower)
            else Double.NaN
        val observed get() = if (valid) holdout.rate else Double.NaN
    }
    data class Sample(val minute: Int,val side: Int,val ratio: Double,val won: Boolean)
    class Model {
        private val train = Array(14*2*RATIOS.size) { Counter() }
        private val test = Array(14*2*RATIOS.size) { Counter() }
        var trainMarkets: Int = 0; private set
        var testMarkets: Int = 0; private set
        fun addMarket(samples: List<Sample>, holdout: Boolean) {
            if (holdout) testMarkets++ else trainMarkets++
            val arr = if (holdout) test else train
            // Each elapsed-minute snapshot occurs at most once per market.
            for (s in samples.distinctBy { it.minute }) {
                if (s.minute !in 1..14 || s.side !in listOf(1,-1) ||
                    !s.ratio.isFinite() || s.ratio < 0.0) continue
                for (i in RATIOS.indices) {
                    if (s.ratio + 1e-10 >= RATIOS[i])
                        arr[index(s.minute,s.side,i)].add(s.won)
                }
            }
        }
        fun evidence(minute: Int,side: Int,ratioIndex: Int): Evidence? {
            if (minute !in 1..14 || side !in listOf(1,-1) ||
                ratioIndex !in RATIOS.indices) return null
            val ix = index(minute,side,ratioIndex)
            return Evidence(minute,side,RATIOS[ratioIndex],train[ix],test[ix])
        }
        fun atDistance(minute: Int,side: Int,actualRatio: Double): Evidence? {
            if (!actualRatio.isFinite() || actualRatio < 0.0) return null
            return RATIOS.indices.reversed().asSequence()
                .filter { RATIOS[it] <= actualRatio+1e-10 }
                .mapNotNull { evidence(minute,side,it) }
                .firstOrNull { it.valid }
        }
        fun minimumRatioForAsk(minute: Int,side: Int,ask: Double?): Evidence? {
            if (ask == null || ask !in 0.01..0.99) return null
            val breakeven = feeAdjustedBreakEven(ask)
            if (!breakeven.isFinite()) return null
            return RATIOS.indices.asSequence()
                .mapNotNull { evidence(minute,side,it) }
                .firstOrNull { it.valid && it.conservative >= breakeven + EXTRA_EDGE }
        }
        fun encode(): String {
            val b = StringBuilder()
            b.append(trainMarkets).append(",").append(testMarkets)
            for (i in train.indices)
                b.append(";").append(train[i].n).append(",").append(train[i].wins)
                    .append(",").append(test[i].n).append(",").append(test[i].wins)
            return b.toString()
        }
        companion object {
            fun decode(raw: String): Model? {
              return try {
                val parts = raw.split(";")
                val model = Model()
                if (parts.size != model.train.size+1) return null
                val header = parts[0].split(",")
                model.trainMarkets = header[0].toInt()
                model.testMarkets = header[1].toInt()
                for (i in model.train.indices) {
                    val fields = parts[i+1].split(",")
                    model.train[i].n=fields[0].toInt()
                    model.train[i].wins=fields[1].toInt()
                    model.test[i].n=fields[2].toInt()
                    model.test[i].wins=fields[3].toInt()
                }
                model
              } catch (_: Exception) { null }
            }
        }
    }
    private fun index(minute: Int,side: Int,index: Int): Int =
        ((minute-1)*2+(if(side==1) 0 else 1))*RATIOS.size+index

    fun normalizedGap(spot: Double,target: Double,acmPct: Double,
        secondsRemaining: Double): Double {
        if (spot<=0 || target<=0 || acmPct<=0 || secondsRemaining<=0 ||
            !spot.isFinite() || !target.isFinite() ||
            !acmPct.isFinite() || !secondsRemaining.isFinite()) return Double.NaN
        val gapPct=abs((spot-target)/target*100.0)
        return gapPct/(acmPct*(secondsRemaining/60.0))
    }

    fun bucket(secondsRemaining: Double): Int =
        if (!secondsRemaining.isFinite() || secondsRemaining<=0)
            0 else ceil(secondsRemaining/60.0).toInt()

    fun feeAdjustedBreakEven(ask: Double): Double {
        val pos=BtcAcmProfitMath.tenDollarPosition(ask,true) ?: return Double.NaN
        return pos.cost/pos.contracts
    }

    data class Decision(
        val side: Int, val label: String, val ratio: Double, val minute: Int,
        val spot: Double, val secondsLeft: Double,
        val selected: Evidence?, val required: Evidence?,
        val ask: Double?, val breakEven: Double, val margin: Double,
        val qualified: Boolean, val reason: String
    )
    fun decide(model: Model?, spot: Double,target: Double,acmPct: Double,
        secondsLeft: Double,yesAsk: Double?,noAsk: Double?): Decision {
        val side=when {
            !spot.isFinite() || !target.isFinite() || target<=0 -> 0
            spot>target -> 1
            spot<target -> -1
            else -> 0
        }
        val label=if(side==1)"YES" else if(side == -1)"NO" else "WAIT"
        val minute=bucket(secondsLeft)
        val ask=if(side==1)yesAsk else if(side == -1)noAsk else null
        val ratio=normalizedGap(spot,target,acmPct,secondsLeft)
        val breakEven=if (ask!=null) feeAdjustedBreakEven(ask) else Double.NaN
        val selected=if(model!=null)model.atDistance(minute,side,ratio) else null
        val required=if(model!=null)model.minimumRatioForAsk(minute,side,ask) else null
        val margin=if(selected!=null && selected.valid)
            selected.conservative-breakEven else Double.NaN
        val reason=when {
            model==null -> "CALIBRATING: no confirmed empirical model yet"
            minute !in 2..8 -> "WAIT: outside 8-to-1 minute buy window"
            side==0 -> "WAIT: BTC exactly at target"
            !ratio.isFinite() -> "WAIT: ACM60 or price unavailable"
            ask==null || !breakEven.isFinite() -> "WAIT: Kalshi buy ask missing"
            required==null -> "WAIT: Kalshi ask too expensive for supported odds"
            ratio+1e-10<required.ratio -> "WAIT: BTC has not reached fair-value buy boundary"
            selected==null || !selected.valid -> "WAIT: insufficient independent markets"
            !margin.isFinite() || margin<EXTRA_EDGE -> "WAIT: not enough edge after fees"
            else -> "BUY $label — empirical bound exceeds Kalshi cost + fees"
        }
        return Decision(side,label,ratio,minute,spot,secondsLeft,
            selected,required,ask,breakEven,
            margin,reason.startsWith("BUY "),reason)
    }
}
