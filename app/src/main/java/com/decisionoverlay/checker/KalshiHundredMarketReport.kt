package com.decisionoverlay.checker

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

/**
 * Discovery dataset ONLY: 100 settled BTC 15-minute markets, their fifteen
 * chronological 1-minute price observations, and the final YES/NO outcome.
 * No pattern mining, scoring, filtering by win rate, simulated trades or fees.
 */
object KalshiHundredMarketReport {
    data class MinutePrice(
        val minute: Int,
        val avgYesPercent: Double,
        val closeOnlyFallback: Boolean
    )

    data class Market(
        val ticker: String,
        val closeTs: Long,
        val yesWon: Boolean,
        val minutes: List<MinutePrice>
    )

    /**
     * 10 percentage bands: [0,10), [10,20), ... [90,100].
     * The bucket 60 means 60.0 through 69.999..., not exact 60 cents.
     */
    internal fun band(percent: Double): Int =
        (floor(percent.coerceIn(0.0, 100.0) / 10.0).toInt() * 10)
            .coerceIn(0, 90)

    private val eastern = ZoneId.of("America/New_York")
    private val dateFormat = DateTimeFormatter.ofPattern(
        "yyyy-MM-dd HH:mm XXX", Locale.US
    )
    private fun time(epochSecond: Long) =
        Instant.ofEpochSecond(epochSecond).atZone(eastern).format(dateFormat)

    private fun oneDecimal(v: Double) =
        String.format(Locale.US, "%.1f", v)

    private fun csvField(text: String): String =
        "\"" + text.replace("\"", "\"\"") + "\""

    internal fun isAllowedSession(closeTs: Long): Boolean {
        val start = Instant.ofEpochSecond(closeTs - 900L).atZone(eastern)
        val end = Instant.ofEpochSecond(closeTs).atZone(eastern)
        // Exact Eastern-time 10:00 AM through 11:00 PM market limits.
        return start.toLocalDate() == end.toLocalDate() &&
            !start.toLocalTime().isBefore(java.time.LocalTime.of(10, 0)) &&
            !end.toLocalTime().isAfter(java.time.LocalTime.of(23, 0))
    }

    fun report(markets: List<Market>, skipped: Int = 0): String {
        if (markets.isEmpty()) return "No settled BTC market minute data available."
        val chronological = markets.distinctBy { it.ticker }
            .sortedWith(compareBy<Market> { it.closeTs }.thenBy { it.ticker })
        val validSession = chronological.filter { isAllowedSession(it.closeTs) }
        val out = StringBuilder(32000)
        out.appendLine("=== KALSHI BTC: 100-MARKET 15-MINUTE DISCOVERY DATA ===")
        out.appendLine("Settled distinct markets: " + validSession.size +
            "/100 | skipped loads: " + skipped)
        if (validSession.size < 100)
            out.appendLine("INCOMPLETE DATASET: only " + validSession.size +
                " markets; do not label this a complete 100-market sample.")
        out.appendLine("Session: market START >=10:00 AM, market END <=11:00 PM" +
            " America/New_York (handles Eastern daylight saving).")
        out.appendLine("Earliest close: " + time(validSession.firstOrNull()?.closeTs
            ?: chronological.first().closeTs))
        out.appendLine("Latest close: " + time(validSession.lastOrNull()?.closeTs
            ?: chronological.last().closeTs))
        out.appendLine("M01 is the FIRST completed 1-minute candle; " +
            "M15 is the LAST completed 1-minute candle, in chronological order.")
        out.appendLine("YES percentage = average of reported 1-minute OHLC " +
            "YES bid and ask values (8 numbers), expressed as a percentage.")
        out.appendLine("If any OHLC element is unavailable, that minute uses " +
            "the observed 1-minute closing YES bid/ask midpoint; " +
            "see fallback totals below. OHLC4 is a price proxy, " +
            "NOT a true time-weighted or trade-volume-weighted average.")
        out.appendLine("BAND matrix groups into 10-point ranges: " +
            "0=[0,10), 10=[10,20), ... 90=[90,100].")
        out.appendLine("RAW matrix keeps the 1-decimal observed average " +
            "to make the data reproducible.")
        out.appendLine("Missing/ineligible minute candles show NA, NEVER " +
            "a guessed value or a previous candle carried forward.")
        out.appendLine("Settlement is UP=YES or DOWN=NO. This collects " +
            "raw data ONLY: no pattern selection, hit rate, win rate, " +
            "purchase prices, fees, or 1,000-market test.")
        out.appendLine()

        var complete = 0
        var observed = 0
        var fallback = 0
        var up = 0
        var down = 0
        for (market in validSession) {
            val n = market.minutes.distinctBy { it.minute }
                .count { it.minute in 1..15 }
            if (n == 15) complete++
            observed += n
            fallback += market.minutes.filter { it.minute in 1..15 }
                .distinctBy { it.minute }.count { it.closeOnlyFallback }
            if (market.yesWon) up++ else down++
        }
        out.appendLine("QUALITY: complete 15-minute rows=" + complete + "/" +
            validSession.size + " | observed minute cells=" + observed + "/" +
            (validSession.size * 15) + " | close-only fallback cells=" +
            fallback + " | settled UP=" + up + " DOWN=" + down)
        out.appendLine()

        val header = buildString {
            append("market_number,ticker,market_start_et,market_end_et,outcome,observed_minutes")
            for (minute in 1..15) append(",M").append(
                minute.toString().padStart(2, '0'))
        }
        fun appendTable(raw: Boolean) {
            out.appendLine(if (raw)
                "=== RAW AVERAGE YES PERCENT PER MINUTE (1 DECIMAL) ==="
                else "=== TEN-BAND YES PERCENT MATRIX (PRIMARY) ===")
            out.appendLine(header)
            validSession.forEachIndexed { index, market ->
                val byMinute = market.minutes.filter { it.minute in 1..15 }
                    .distinctBy { it.minute }.associateBy { it.minute }
                out.append(index + 1).append(',')
                    .append(csvField(market.ticker)).append(',')
                    .append(csvField(time(market.closeTs - 900L))).append(',')
                    .append(csvField(time(market.closeTs))).append(',')
                    .append(if (market.yesWon) "UP" else "DOWN").append(',')
                    .append(byMinute.size)
                for (minute in 1..15) {
                    out.append(',')
                    val price = byMinute[minute]?.avgYesPercent
                    if (price == null || !price.isFinite()) {
                        out.append("NA")
                    } else if (raw) {
                        out.append(oneDecimal(price))
                    } else {
                        out.append(band(price))
                    }
                }
                out.appendLine()
            }
            out.appendLine()
        }
        appendTable(raw = false)
        appendTable(raw = true)
        out.appendLine("=== END 100-MARKET DISCOVERY DATA ===")
        out.appendLine("Share both matrices to discover recurring " +
            "2-point, 3-point, and reversal paths. A separate, untouched " +
            "1,000-market sample can be tested AFTER discovery.")
        return out.toString()
    }
}
