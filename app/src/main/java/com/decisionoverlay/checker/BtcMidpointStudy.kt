package com.decisionoverlay.checker

import java.util.Locale
import kotlin.math.abs

/**
 * Read-only research using Binance.US BTC/USDT one-minute candles.
 *
 * At XX:07 and XX:08 (7 and 8 minutes ELAPSED), observe the OPEN
 * of that minute. This is a price available at the exact observation instant.
 * Target: OHLC4 of the completed minute BEFORE XX:00/15/30/45.
 * End: CLOSE of the 15th minute. Neither price is Kalshi's CF index.
 *
 * An exact 0.375000% match is implausible; include 0.350%-<0.400%
 * and disclose this tolerance in every report.
 */
class BtcMidpointStudy {
    data class Bar(
        val timestamp: Long, val open: Double,
        val high: Double, val low: Double, val close: Double
    ) {
        val ohlc4: Double get() = (open + high + low + close) / 4.0
    }

    data class Counts(
        var total: Int = 0, var stayed: Int = 0,
        var crossed: Int = 0, var tied: Int = 0, var everTouched: Int = 0
    ) {
        fun percent(n: Int): String =
            if (total == 0) "N/A"
            else String.format(Locale.US, "%.1f%%", n * 100.0 / total)
        fun report(sideName: String): String =
            "$sideName: $total matches | STAYED ${percent(stayed)} ($stayed)" +
                " | CROSSED ${percent(crossed)} ($crossed)" +
                " | TIED ${percent(tied)} ($tied)" +
                " | touched target anytime ${percent(everTouched)} ($everTouched)"
    }

    companion object {
        const val MINUTE = 60_000L
        const val QUARTER = 15 * MINUTE
        const val MIN_MATCHES_PER_MINUTE = 100
        const val MAX_DAYS = 540
        const val CENTER_PERCENT = 0.375
        const val MIN_PERCENT = 0.35
        const val MAX_PERCENT = 0.40
        val ELAPSED_MINUTES = listOf(7, 8)
    }

    private val counted = mutableMapOf(
        7 to mutableMapOf(1 to Counts(), -1 to Counts()),
        8 to mutableMapOf(1 to Counts(), -1 to Counts())
    )
    var eligibleMarkets: Int = 0
        private set
    var incompleteMarkets: Int = 0
        private set

    fun counts(elapsed: Int, direction: Int): Counts =
        counted.getValue(elapsed).getValue(direction).copy()

    fun total(elapsed: Int): Int =
        counted.getValue(elapsed).values.sumOf { it.total }

    fun complete(): Boolean =
        ELAPSED_MINUTES.all { total(it) >= MIN_MATCHES_PER_MINUTE }

    /**
     * Called once per distinct completed market. No later candle is used
     * to choose a match; future bars are inspected ONLY to score its outcome.
     */
    fun record(start: Long, candles: Map<Long, Bar>) {
        val targetBar = candles[start - MINUTE]
        val endBar = candles[start + 14 * MINUTE]
        if (targetBar == null || endBar == null ||
            (0..14).any { candles[start + it * MINUTE] == null }) {
            incompleteMarkets++
            return
        }
        val target = targetBar.ohlc4
        val settled = endBar.close
        if (target <= 0.0 || settled <= 0.0 ||
            !target.isFinite() || !settled.isFinite()) {
            incompleteMarkets++
            return
        }
        eligibleMarkets++
        for (elapsed in ELAPSED_MINUTES) {
            if (total(elapsed) >= MIN_MATCHES_PER_MINUTE) continue
            val price = candles.getValue(start + elapsed * MINUTE).open
            if (price <= 0.0 || !price.isFinite()) continue
            val gap = (price - target) / target * 100.0
            val distance = abs(gap)
            if (distance < MIN_PERCENT || distance >= MAX_PERCENT) continue
            val side = if (gap > 0.0) 1 else -1
            val result = counted.getValue(elapsed).getValue(side)
            result.total++
            when {
                settled == target -> result.tied++
                (settled - target) * side > 0.0 -> result.stayed++
                else -> result.crossed++
            }
            // Reaching the target at ANY point is different from settling on
            // the opposite side. High/low of the observed candle is future
            // information, used ONLY when scoring what later happened.
            val touched = (elapsed..14).any { minute ->
                val bar = candles.getValue(start + minute * MINUTE)
                if (side > 0) bar.low <= target else bar.high >= target
            }
            if (touched) result.everTouched++
        }
    }

    fun report(daysSearched: Int, status: String): String = buildString {
        appendLine("BTC 0.375% MIDPOINT BACKTEST — $status")
        appendLine("Only 10 AM–11 PM New York; one completed 15m market per sample.")
        appendLine("Target: Binance.US OHLC4 of minute before market start.")
        appendLine("Observation: OPEN of XX:07 / XX:08 candle, no future price.")
        appendLine("Settlement: CLOSE of final one-minute candle (XX:14/29/44/59).")
        appendLine("Matches: 0.350% to <0.400% from target, above or below.")
        appendLine("NOT Kalshi's official CF Benchmarks target or settlement.")
        appendLine("STAYED = finishes on same side; CROSSED = finishes opposite side.")
        appendLine("TIED = finishes exactly on target (Kalshi YES would win a tie).")
        appendLine("TOUCHED = price reached target at any later instant,")
        appendLine("including cases where it recovered and finished on original side.")
        appendLine("Days searched: $daysSearched | complete markets checked: $eligibleMarkets")
        appendLine("Incomplete markets skipped: $incompleteMarkets")
        appendLine()
        for (elapsed in ELAPSED_MINUTES) {
            appendLine("--- XX:${String.format(Locale.US, "%02d", elapsed)} " +
                "(${15 - elapsed} minutes LEFT) ---")
            appendLine("Matched ${total(elapsed)}/$MIN_MATCHES_PER_MINUTE required cases")
            appendLine(counts(elapsed, 1).report("ABOVE +0.375%"))
            appendLine(counts(elapsed, -1).report("BELOW -0.375%"))
            val a = counts(elapsed, 1)
            val b = counts(elapsed, -1)
            val n = a.total + b.total
            if (n > 0) appendLine(String.format(Locale.US,
                "ALL SIDES: stayed %.1f%% (%d/%d); crossed %.1f%% (%d/%d); " +
                    "tied %.1f%% (%d/%d)",
                100.0 * (a.stayed + b.stayed) / n, a.stayed + b.stayed, n,
                100.0 * (a.crossed + b.crossed) / n, a.crossed + b.crossed, n,
                100.0 * (a.tied + b.tied) / n, a.tied + b.tied, n))
            appendLine()
        }
        appendLine("Historical observations only, not a guaranteed future probability.")
        appendLine("There are 100 total examples PER minute, not 100 per direction.")
    }
}
