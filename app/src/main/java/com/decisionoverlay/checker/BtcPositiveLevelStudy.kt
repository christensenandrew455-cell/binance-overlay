package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.floor

/**
 * Descriptive first-touch and per-minute calibration of the LIVE CHART'S
 * positive distance-to-target index. The index is NOT a probability or
 * a Kalshi contract price. This report does not place orders.
 *
 * Observations use the already completed one-minute candles in the shared
 * 1,000-market Binance backtest. Settlement (minute 15) is NOT a signal.
 */
internal object BtcPositiveLevelStudy {
    private val LEVELS = listOf(50, 60, 70, 80, 90)
    private val RETRACEMENTS = listOf(70, 80, 90)

    private data class Counts(
        var n: Int = 0,
        var up: Int = 0,
        var sumElapsed: Int = 0
    ) {
        fun add(upWon: Boolean, elapsed: Int = 0) {
            n++
            if (upWon) up++
            sumElapsed += elapsed
        }
    }

    private fun pct(up: Int, n: Int): String =
        if (n == 0) "n/a"
        else String.format(Locale.US, "%.1f%%", 100.0 * up / n)

    private fun average(sum: Int, n: Int): String =
        if (n == 0) "n/a"
        else String.format(Locale.US, "%.2f", sum.toDouble() / n)

    /** +50 = [50,60), +60 = [60,70), ... +90 = the capped value 90. */
    internal fun positiveBand(signed: Double): Int? {
        if (!signed.isFinite() || signed <= 0.0 || signed < 50.0) return null
        return (floor(signed / 10.0).toInt() * 10).coerceIn(50, 90)
    }

    fun report(markets: List<BinanceTwentySevenPatternReport.Market>): String {
        val unique = markets.distinctBy { it.startMs }.sortedBy { it.startMs }
        if (unique.isEmpty()) return "No completed BTC markets available for chart-level study."
        val snapshot = mutableMapOf<Pair<Int, Int>, Counts>()
        val firstByMinute = mutableMapOf<Pair<Int, Int>, Counts>()
        val firstOverall = mutableMapOf<Int, Counts>()
        val retraced = mutableMapOf<Int, Counts>()

        for (market in unique) {
            val candles = market.minutes.asSequence()
                .filter { it.minute in 1..14 && it.signedChartLevel.isFinite() }
                .sortedBy { it.minute }.distinctBy { it.minute }.toList()
            for (obs in candles) {
                val band = positiveBand(obs.signedChartLevel) ?: continue
                snapshot.getOrPut((15 - obs.minute) to band) { Counts() }
                    .add(market.upWon)
            }
            for (threshold in LEVELS) {
                // A jump straight to +90 also qualifies as having reached +80.
                // Only the FIRST close meeting each threshold counts per market.
                val first = candles.firstOrNull {
                    it.signedChartLevel >= threshold.toDouble()
                } ?: continue
                firstByMinute.getOrPut(threshold to (15 - first.minute)) { Counts() }
                    .add(market.upWon)
                firstOverall.getOrPut(threshold) { Counts() }
                    .add(market.upWon, first.minute)
                if (threshold in RETRACEMENTS) {
                    // No limit on elapsed time. Must later decline by >=20
                    // chart points while STILL ABOVE the BTC target.
                    val later = candles.firstOrNull {
                        it.minute > first.minute &&
                            it.signedChartLevel > 0.0 &&
                            it.signedChartLevel <= threshold - 20.0
                    }
                    if (later != null) retraced.getOrPut(threshold) { Counts() }
                        .add(market.upWon, later.minute - first.minute)
                }
            }
        }

        return buildString {
            appendLine("=== BTC POSITIVE CHART LEVELS: ACTUAL UP FINISH RATES ===")
            appendLine("Distinct completed 15m markets: ${unique.size}; source Binance.US BTC/USDT 1m closes.")
            appendLine("UP means final BTC close STRICTLY above the market's original target; tied markets excluded by the scanner.")
            appendLine("The signed chart index is a custom price-distance/time score, NOT actual 50-90% odds.")
            appendLine("ONLY positive (+) observations are counted; a BTC close at the target or below is excluded.")
            appendLine("At 15 min left no 1m candle is closed; at 0 min left settlement is known, NOT an entry. Both are n/a.")
            appendLine("Current bands: +50=[50,60), +60=[60,70), +70=[70,80), +80=[80,90), +90=capped 90.")
            appendLine("Every market contributes at most one snapshot PER MINUTE; repeated minutes across a market are correlated.")
            appendLine("First touch = first CLOSED 1m candle on which the positive score reaches or exceeds that threshold.")
            appendLine("A first +90 touch also counts as first reaching +80/+70/+60/+50 if not seen earlier.")
            appendLine("Intraminute touches are unobservable and are NOT invented.")
            appendLine("Historical description only; selection, live Kalshi prices, fees, fills and profits are not tested.")
            appendLine()
            appendLine("=== CURRENT POSITIVE BAND AT EACH MINUTE (NOT FIRST TOUCH) ===")
            appendLine("minutes_left,band,markets,finished_up,finished_down,actual_up_finish_pct")
            for (left in 15 downTo 0) for (band in LEVELS) {
                if (left == 15 || left == 0) {
                    appendLine("$left,+$band,n/a,n/a,n/a,n/a")
                } else {
                    val c = snapshot[left to band] ?: Counts()
                    appendLine("$left,+$band,${c.n},${c.up},${c.n-c.up},${pct(c.up,c.n)}")
                }
            }
            appendLine()
            appendLine("=== FIRST TIME EACH POSITIVE THRESHOLD IS REACHED ===")
            appendLine("threshold,minutes_left,first_hits,finished_up,finished_down,actual_up_finish_pct")
            for (threshold in LEVELS) for (left in 15 downTo 0) {
                if (left == 15 || left == 0) {
                    appendLine("+$threshold,$left,n/a,n/a,n/a,n/a")
                } else {
                    val c = firstByMinute[threshold to left] ?: Counts()
                    appendLine("+$threshold,$left,${c.n},${c.up},${c.n-c.up},${pct(c.up,c.n)}")
                }
            }
            appendLine()
            appendLine("=== FIRST TOUCH SUMMARY (AVERAGE LOCATION OF FIRST HIT) ===")
            appendLine("threshold,markets_reached,markets_never_reached,first_hit_rate_pct,avg_minutes_elapsed,avg_minutes_left,finished_up,finished_down,actual_up_finish_pct")
            for (threshold in LEVELS) {
                val c = firstOverall[threshold] ?: Counts()
                val meanElapsed = if (c.n == 0) "n/a" else average(c.sumElapsed, c.n)
                val meanLeft = if (c.n == 0) "n/a" else average(15*c.n-c.sumElapsed,c.n)
                appendLine("+$threshold,${c.n},${unique.size-c.n},${pct(c.n,unique.size)}," +
                    "$meanElapsed,$meanLeft,${c.up},${c.n-c.up},${pct(c.up,c.n)}")
            }
            appendLine()
            appendLine("=== FIRST HIGH THEN 20-POINT DROP ON POSITIVE SIDE ===")
            appendLine("First completed touch of +HIGH, then ANY later completed positive score <= +LOW; one event per market.")
            appendLine("A jump past the low threshold qualifies; no maximum waiting period, but both observations must precede settlement.")
            appendLine("path,markets,finished_up,finished_down,actual_up_finish_pct,avg_minutes_from_high_to_drop")
            for (high in RETRACEMENTS) {
                val low = high - 20
                val c = retraced[high] ?: Counts()
                appendLine("+$high -> <=+$low,${c.n},${c.up},${c.n-c.up}," +
                    "${pct(c.up,c.n)},${average(c.sumElapsed,c.n)}")
            }
            appendLine()
            appendLine("IMPORTANT: These rows describe settlement outcomes conditioned on past chart observations.")
            appendLine("They are not calibrated probabilities for new markets or evidence of a profitable trade after fees.")
            appendLine("Use a later untouched sample to validate any pattern selected from this report.")
        }
    }
}
