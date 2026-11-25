package com.decisionoverlay.checker

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Fixed hypothetical fill simulation. Never uses future data to choose an alert. */
internal object BtcDailyProfitReport {
    private val zone = ZoneId.of("America/New_York")
    private const val EXPECTED_MARKETS = 52

    data class Config(val priceCents: Int = 60, val contracts: Int = 100,
        val feeCents: Int = 0) {
        init {
            require(priceCents in 1..99 && contracts in 1..100000 &&
                feeCents in 0..10000000)
        }
        val cost: Long get() = priceCents.toLong() * contracts
        val win: Long get() = 100L * contracts - cost - feeCents
        val loss: Long get() = -cost - feeCents
    }

    data class Day(val date: LocalDate, val markets: Int, val wins: Int, val losses: Int,
        val config: Config) {
        val trades get() = wins + losses
        val net: Long get() = wins * config.win + losses * config.loss
        val grossCost: Long get() = trades * config.cost
        val fees: Long get() = trades.toLong() * config.feeCents
        val complete get() = markets == EXPECTED_MARKETS
        val outcome get() = when {
            markets == 0 -> "MISSING"
            trades == 0 -> "NO TRADES"
            net > 0 -> "PROFIT"
            net < 0 -> "LOSS"
            else -> "BREAK EVEN"
        }
    }

    data class Result(val days: List<Day>, val config: Config) {
        val observed get() = days.filter { it.markets > 0 }
        val full get() = days.filter { it.complete }
        val totalMarkets get() = observed.sumOf { it.markets }
        val net get() = observed.sumOf { it.net }
        val wins get() = observed.sumOf { it.wins }
        val losses get() = observed.sumOf { it.losses }

        private fun StringBuilder.summary(label: String, sample: List<Day>) {
            appendLine(label + " (" + sample.size + " days)")
            if (sample.isEmpty()) { appendLine("N/A"); return }
            val total = sample.sumOf { it.net }
            val profit = sample.count { it.net > 0 }
            val negative = sample.count { it.net < 0 }
            val even = sample.count { it.net == 0L && it.trades > 0 }
            val noTrades = sample.count { it.trades == 0 }
            val sorted = sample.map { it.net }.sorted()
            val median = (sorted[(sorted.size - 1) / 2].toDouble() +
                sorted[sorted.size / 2]) / 2.0
            appendLine("Total net: " + money(total))
            appendLine("Average net/day: " + money(total.toDouble() / sample.size))
            appendLine("Median net/day: " + money(median))
            appendLine("Profitable: " + profit + " (" + pct(profit, sample.size) + ")")
            appendLine("Negative: " + negative + " (" + pct(negative, sample.size) + ")")
            appendLine("Break even with trades: " + even + " (" + pct(even, sample.size) + ")")
            appendLine("No-trade days: " + noTrades + " (" + pct(noTrades, sample.size) + ")")
            appendLine("Zero net incl. no-trade: " + (even + noTrades))
            appendLine("Best / worst: " + money(sorted.last()) + " / " + money(sorted.first()))
            appendLine("Daily net distribution:")
            val buckets = listOf(
                "Loss at least \$200" to { n: Long -> n <= -20000L },
                "Loss \$100 to <\$200" to { n: Long -> n > -20000L && n <= -10000L },
                "Loss >\$0 to <\$100" to { n: Long -> n > -10000L && n < 0L },
                "Zero" to { n: Long -> n == 0L },
                "Profit >\$0 to <\$100" to { n: Long -> n > 0L && n < 10000L },
                "Profit \$100 to <\$200" to { n: Long -> n >= 10000L && n < 20000L },
                "Profit \$200 to <\$300" to { n: Long -> n >= 20000L && n < 30000L },
                "Profit \$300 or more" to { n: Long -> n >= 30000L })
            for ((name, check) in buckets) {
                val count = sample.count { check(it.net) }
                appendLine("  " + name + ": " + count + " (" + pct(count, sample.size) + ")")
            }
            appendLine()
        }

        fun report(): String = buildString {
            appendLine("=== DAILY BTC P&L — 1,000 MARKET TEST ===")
            appendLine("Markets: " + totalMarkets + "/1000; dates: 10 AM–11 PM Eastern.")
            if (totalMarkets != 1000) appendLine("INCOMPLETE: provisional results.")
            appendLine("First qualifying existing BUY alert only, at candle close.")
            appendLine("Early warnings are NOT trades. No rule changes or refitting.")
            appendLine("Assumed entry: " + config.priceCents + "¢ x " + config.contracts +
                " contracts = " + money(config.cost) + " per trade.")
            appendLine("Assumed total fee/trade: " + money(config.feeCents.toLong()))
            appendLine("Net win: " + money(config.win) + "; net loss: " + money(config.loss))
            appendLine("Trades: " + (wins + losses) + "; wins: " + wins +
                "; losses: " + losses + "; win rate: " + pct(wins, wins + losses))
            appendLine("Total simulated net: " + money(net))
            appendLine("Hypothetical fixed fills; no live quotes, fill delays, bankroll limits,")
            appendLine("or intraminute recrosses modeled. Binance midpoint settlement proxy.")
            appendLine("Frozen rules were researched historically; overlap is possible.")
            appendLine("Full days require all 52 markets; partial days are marked.")
            appendLine("Missing dates excluded from statistics: " + days.count { it.markets == 0 })
            appendLine()
            summary("ALL OBSERVED DAYS (including partial)", observed)
            summary("FULL DAYS ONLY", full)
            appendLine("DATE | MARKETS | TRADES | W-L | NET | OUTCOME")
            for (d in days) {
                appendLine(d.date.toString() + " | " + d.markets + "/52 | " +
                    d.trades + " | " + d.wins + "-" + d.losses + " | " +
                    (if (d.markets == 0) "N/A" else money(d.net)) + " | " +
                    d.outcome + if (!d.complete && d.markets > 0) " (PARTIAL)" else "")
            }
        }

        fun csv(): String = buildString {
            appendLine("date_et,markets,full_day,trades,wins,losses,win_rate_pct,entry_cents,contracts_per_trade,fee_cents_per_trade,entry_cost_usd,total_fees_usd,net_pnl_usd,outcome")
            for (d in days) {
                val missing = d.markets == 0
                appendLine(listOf(d.date, d.markets, d.complete, d.trades, d.wins,
                    d.losses, if (d.trades == 0) "" else decimal(100.0 * d.wins / d.trades),
                    config.priceCents, config.contracts, config.feeCents,
                    if (missing) "" else decimal(d.grossCost / 100.0),
                    if (missing) "" else decimal(d.fees / 100.0),
                    if (missing) "" else decimal(d.net / 100.0), d.outcome).joinToString(","))
            }
        }
    }

    fun analyze(markets: List<BinanceTwentySevenPatternReport.Market>,
        config: Config = Config()): Result {
        val ordered = markets.filter { BinanceTwentySevenPatternReport.isAllowedSession(it.startMs) }
            .distinctBy { it.startMs }.sortedBy { it.startMs }
        if (ordered.isEmpty()) return Result(emptyList(), config)
        val byDate = ordered.groupBy { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() }
        val days = ArrayList<Day>()
        var date = byDate.keys.first()
        val last = byDate.keys.last()
        while (!date.isAfter(last)) {
            val group = byDate[date].orEmpty()
            var wins = 0
            var losses = 0
            for (market in group) {
                val history = ArrayList<BtcChartProxyMath.Sample>()
                for (m in market.minutes.filter { it.minute in 1..14 && it.signedChartLevel.isFinite() }
                    .sortedBy { it.minute }.distinctBy { it.minute }) {
                    history.add(BtcChartProxyMath.Sample(15 - m.minute, m.signedChartLevel))
                    val signal = KalshiLiveSignalRules.matchingSignedSignals(history)
                        .firstOrNull() ?: continue
                    if (signal.rule.buyUp == market.upWon) wins++ else losses++
                    break // one buy per market, like the live service
                }
            }
            days.add(Day(date, group.size, wins, losses, config))
            date = date.plusDays(1)
        }
        return Result(days, config)
    }

    private fun decimal(n: Double) = String.format(Locale.US, "%.2f", n)
    private fun money(cents: Double) = (if (cents < 0) "-\$" else "\$") +
        decimal(kotlin.math.abs(cents) / 100.0)
    private fun money(cents: Long) = money(cents.toDouble())
    private fun pct(n: Int, d: Int) = if (d == 0) "N/A"
        else String.format(Locale.US, "%.1f%%", 100.0 * n / d)
}
