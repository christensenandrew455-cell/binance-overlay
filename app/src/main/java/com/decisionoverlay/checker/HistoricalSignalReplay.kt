package com.decisionoverlay.checker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Strictly causal historical *candle-only* replay. Trades and order-book
 * snapshots are not present in Binance candle history and are not invented.
 * This does not reproduce Kalshi CF Benchmarks settlements or past tradable asks.
 *
 * Split by time: oldest half is for choosing a rule, newest half is held out.
 * One first signal per 15-minute round; WAIT is not counted as a correct trade.
 */
object HistoricalSignalReplay {
    private const val MINUTE = 60_000L
    private const val QUARTER = 15L * MINUTE
    private const val FIVE = 5L * MINUTE
    private const val ROUNDS = 2400
    // 180 completed 5m candles require at least 900 minutes of warm-up.
    private const val WARMUP_MINUTES = 950
    private const val TARGET_TEST_SIGNALS = 100
    private const val MAX_PAGES = 105
    // Only observe after minute 10 or 11 of the round: 5 or 4 minutes left.
    internal val entryMinuteOffsets: IntRange = 10..11
    val supportedCoins = listOf("BTC", "ETH", "SOL", "XRP", "DOGE", "BNB", "HYPE", "NEAR", "ZEC")

    private val candidates = listOf(
        SignalEngine.CandleRules("Original: score 4/7"),
        SignalEngine.CandleRules("Stricter: score 5/7", minScore = 5),
        SignalEngine.CandleRules("More volume: 1.25x", minVolume = 1.25),
        SignalEngine.CandleRules("More momentum: +0.6 ATR", minMomentum = 0.60),
        SignalEngine.CandleRules("Require higher/lower structure", requireStructure = true),
        SignalEngine.CandleRules("Require rejection wick", requireWick = true),
        SignalEngine.CandleRules("Tighter target reach", travelMultiplier = 0.75)
    )

    data class Attempt(
        val marketStart: Long,
        val observationAt: Long,
        val direction: Int,
        val target: Double,
        val priceAtSignal: Double,
        val endPrice: Double,
        val correct: Boolean
    )

    private data class Tally(
        val training: MutableList<Attempt> = mutableListOf(),
        val testing: MutableList<Attempt> = mutableListOf()
    )

    /** The latest N eligible 15m markets, not merely the last N calendar markets.
     *  Returns oldest-to-newest for chronological training/test splits.
     */
    fun lastEligibleStarts(lastCompleteEnd: Long, count: Int): List<Long> {
        require(count in 1..10_000)
        val starts = ArrayList<Long>(count)
        var start = lastCompleteEnd - QUARTER
        while (starts.size < count) {
            if (TradingHours.allowedMarketStart(start)) starts.add(start)
            start -= QUARTER
        }
        starts.reverse()
        return starts
    }

    fun run(coin: String, progress: (String) -> Unit): String {
        require(coin in supportedCoins)
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).build()
        val now = System.currentTimeMillis()
        val lastCompleteEnd = (now / QUARTER) * QUARTER
        // Count ROUNDS inside allowed hours, going farther back to replace excluded hours.
        val eligibleStarts = lastEligibleStarts(lastCompleteEnd, ROUNDS)
        val firstRound = eligibleStarts.first()
        val calendarRoundsSearched = ((lastCompleteEnd - firstRound) / QUARTER).toInt()
        val skippedOutsideHours = calendarRoundsSearched - eligibleStarts.size
        val from = firstRound - WARMUP_MINUTES * MINUTE
        val until = lastCompleteEnd - 1
        progress("Found " + eligibleStarts.size + " eligible 10 AM–11 PM markets across " +
            calendarRoundsSearched + " calendar rounds. Downloading " + coin +
            " candles (may take a few minutes)...")
        val minuteBars = download(client, coin, from, until, progress)
        require(minuteBars.isNotEmpty()) { "Binance.US returned no history" }
        val byMinute = minuteBars.withIndex().associate { it.value.openedAt to it.index }
        val fiveBars = makeFiveMinute(minuteBars)
        val byFive = fiveBars.withIndex().associate { it.value.openedAt to it.index }
        val tallies = candidates.associateWith { Tally() }
        var validRounds = 0
        var skippedMissing = 0
        var heldOutRounds = 0
        var trainRounds = 0

        for ((roundIndex, start) in eligibleStarts.withIndex()) {
            // An OHLC4 proxy for the 60-second average PRECEDING market start.
            // Neither this nor the final OHLC4 are the official CF index.
            val targetIndex = byMinute[start - MINUTE]
            val finishIndex = byMinute[start + 14 * MINUTE]
            if (targetIndex == null || finishIndex == null) {
                skippedMissing++
                continue
            }
            val targetBar = minuteBars[targetIndex]
            val endBar = minuteBars[finishIndex]
            val target = ohlc4(targetBar)
            val settlement = ohlc4(endBar)
            if (target <= 0 || settlement <= 0 || finishIndex - targetIndex != 15 ||
                !continuous(minuteBars, targetIndex, finishIndex)) {
                skippedMissing++
                continue
            }
            val isTrain = roundIndex < ROUNDS / 2
            if (isTrain) trainRounds++ else heldOutRounds++
            validRounds++
            val firstSignals = HashMap<SignalEngine.CandleRules, Attempt>()
            for (minuteOffset in entryMinuteOffsets) {
                val observedAt = start + minuteOffset * MINUTE
                val oneIndex = byMinute[observedAt - MINUTE] ?: continue
                // Last 5m candle that COMPLETED before observedAt.
                val fiveOpen = (observedAt / FIVE - 1) * FIVE
                val fiveIndex = byFive[fiveOpen] ?: continue
                if (oneIndex < 179 || fiveIndex < 179) continue
                val one = minuteBars.subList(oneIndex - 179, oneIndex + 1)
                val five = fiveBars.subList(fiveIndex - 179, fiveIndex + 1)
                val spot = minuteBars[oneIndex].close
                for (rules in candidates) {
                    if (rules in firstSignals) continue
                    val signal = SignalEngine.replayCandle(
                        one, five, spot, target, (15 - minuteOffset).toDouble(), rules
                    )
                    if (signal.direction == 0) continue
                    val won = proxyWon(signal.direction, settlement, target)
                    firstSignals[rules] = Attempt(start, observedAt, signal.direction,
                        target, spot, settlement, won)
                }
                if (firstSignals.size == candidates.size) break
            }
            for ((rule, attempt) in firstSignals) {
                val tally = tallies.getValue(rule)
                if (isTrain) tally.training.add(attempt) else tally.testing.add(attempt)
            }
            if (roundIndex % 80 == 0) {
                progress("Replaying " + coin + ": " + (roundIndex + 1) + "/" + ROUNDS +
                    " markets. No future candles used in decisions.")
            }
        }

        require(validRounds >= 150) { "Too few complete 15-minute rounds: " + validRounds }
        // Rule selection is based ONLY on the older training portion, never test outcomes.
        // Below 25 training signals, keep original rule instead of optimizing noise.
        val eligible = candidates.filter { tallies.getValue(it).training.size >= 25 }
        val selected = eligible.maxWithOrNull(
            compareBy<SignalEngine.CandleRules> {
                val samples = tallies.getValue(it).training
                wilsonLower(samples.count { a -> a.correct }, samples.size)
            }.thenBy { tallies.getValue(it).training.size }
        ) ?: candidates.first()
        val report = StringBuilder()
        report.appendLine("15-MIN CANDLE SIGNAL BACKTEST — " + coin)
        report.appendLine("Run: " + Instant.ofEpochMilli(now))
        report.appendLine("Trading hours: 10:00 AM–11:00 PM America/New_York (DST-aware).")
        report.appendLine("Only rounds beginning 10:00–22:45 New York time are eligible.")
        report.appendLine("BUY evaluation: only 5 or 4 whole minutes remaining (never earlier or in final 3m).")
        report.appendLine("Calendar rounds searched: " + calendarRoundsSearched)
        report.appendLine("Skipped OUTSIDE trading hours: " + skippedOutsideHours)
        report.appendLine("Eligible markets selected for testing: " + eligibleStarts.size +
            "/" + ROUNDS)
        report.appendLine("Completed IN-WINDOW markets: " + validRounds +
            " (missing inside window: " + skippedMissing + ")")
        report.appendLine("Chronological split: older " + trainRounds +
            " training markets; newer " + heldOutRounds + " unseen testing markets.")
        report.appendLine("Goal: 100 ACTUAL BUY signals on held-out markets, " +
            "one first signal per market; WAIT is NOT a win.")
        report.appendLine("No look-ahead: only previously closed 1m/5m candles are visible at entry.")
        report.appendLine("Target: Binance.US previous minute OHLC4 proxy. " +
            "End: Binance.US final minute OHLC4 proxy.")
        report.appendLine("Kalshi uses CF Benchmarks 60-second average, so exact results can differ.")
        report.appendLine("No historic order book / aggressor trades in candles. " +
            "Live buyer/seller confirmation cannot be backtested here.")
        report.appendLine("No historic Kalshi asks, spreads or fees: accuracy is NOT profit.")
        report.appendLine()
        report.appendLine("=== OLDER TRAINING: WHICH FILTERS HELP? ===")
        for (rule in candidates) {
            val train = tallies.getValue(rule).training
            report.appendLine(rule.name + ": " + accuracy(train) + " | calls " +
                train.size + "/" + trainRounds + " markets")
        }
        report.appendLine("Selected on TRAINING ONLY: " + selected.name)
        if (eligible.isEmpty()) report.appendLine(
            "WARNING: No rule had 25 training calls; using original instead of tuning noise."
        )
        report.appendLine()

        val base = tallies.getValue(candidates.first()).testing.takeLast(TARGET_TEST_SIGNALS)
        val tested = tallies.getValue(selected).testing.takeLast(TARGET_TEST_SIGNALS)
        report.appendLine("=== NEWER, UNSEEN VALIDATION ===")
        report.appendLine("Original rule: " + accuracy(base) +
            " | " + base.size + "/100 calls (out of " + heldOutRounds + " markets)")
        report.appendLine("Selected rule: " + accuracy(tested) +
            " | " + tested.size + "/100 calls (out of " + heldOutRounds + " markets)")
        report.appendLine("Selected rule says WAIT on " +
            (heldOutRounds - tallies.getValue(selected).testing.size) +
            "/" + heldOutRounds + " held-out markets.")
        report.appendLine("Total selected signals in held-out half: " +
            tallies.getValue(selected).testing.size)
        if (tested.size < TARGET_TEST_SIGNALS) report.appendLine(
            "LESS THAN 100 CALLS AVAILABLE: result is " + tested.size +
            "/100, NOT a 100-trade accuracy estimate. Do not fill missing trades with WAIT."
        )
        val yesCalls = tested.filter { it.direction == 1 }
        val noCalls = tested.filter { it.direction == -1 }
        report.appendLine("UP/YES crossings: " + accuracy(yesCalls))
        report.appendLine("DOWN/NO crossings: " + accuracy(noCalls))
        report.appendLine()
        report.appendLine("=== EACH OF THE MOST RECENT VALIDATION CALLS (max 100) ===")
        for ((i, entry) in tested.withIndex()) {
            report.appendLine(String.format(Locale.US,
                "%03d | %s | %s | remaining %d min | target %.8f | " +
                    "entry %.8f | end %.8f | %s",
                i + 1, Instant.ofEpochMilli(entry.marketStart),
                if (entry.direction == 1) "BUY UP" else "BUY DOWN",
                (entry.marketStart + QUARTER - entry.observationAt) / MINUTE,
                entry.target, entry.priceAtSignal, entry.endPrice,
                if (entry.correct) "CORRECT" else "WRONG"))
        }
        report.appendLine()
        report.appendLine("Interpretation: A validation hit rate measures settlement direction " +
            "only; it cannot establish a profitable Kalshi strategy without entry prices.")
        return report.toString()
    }

    /** Kalshi YES wins on a tie; NO must finish strictly below target. */
    fun proxyWon(direction: Int, finalPrice: Double, target: Double): Boolean =
        if (direction == 1) finalPrice >= target
        else if (direction == -1) finalPrice < target
        else false

    private fun accuracy(events: List<Attempt>): String {
        if (events.isEmpty()) return "0/0 (NO CALLS)"
        val wins = events.count { it.correct }
        return String.format(Locale.US, "%d/%d correct = %.1f%%",
            wins, events.size, 100.0 * wins / events.size)
    }

    private fun wilsonLower(successes: Int, n: Int): Double {
        if (n == 0) return 0.0
        val p = successes.toDouble() / n
        val z = 1.96
        val denom = 1.0 + z * z / n
        val center = p + z * z / (2.0 * n)
        val margin = z * sqrt(p * (1.0 - p) / n + z * z / (4.0 * n * n))
        return max(0.0, (center - margin) / denom)
    }

    private fun ohlc4(bar: SignalEngine.Bar): Double =
        (bar.open + bar.high + bar.low + bar.close) / 4.0

    private fun continuous(bars: List<SignalEngine.Bar>, from: Int, to: Int): Boolean {
        for (i in from + 1..to) {
            if (bars[i].openedAt - bars[i - 1].openedAt != MINUTE) return false
        }
        return true
    }

    private fun makeFiveMinute(one: List<SignalEngine.Bar>): List<SignalEngine.Bar> {
        return one.groupBy { it.openedAt / FIVE }.values.mapNotNull { group ->
            if (group.size != 5) return@mapNotNull null
            val sorted = group.sortedBy { it.openedAt }
            if (sorted.first().openedAt % FIVE != 0L ||
                !continuous(sorted, 0, 4)) return@mapNotNull null
            SignalEngine.Bar(
                openedAt = sorted.first().openedAt,
                closedAt = sorted.last().closedAt,
                open = sorted.first().open,
                high = sorted.maxOf { it.high },
                low = sorted.minOf { it.low },
                close = sorted.last().close,
                quoteVolume = sorted.sumOf { it.quoteVolume }
            )
        }.sortedBy { it.openedAt }
    }

    private fun download(
        client: OkHttpClient, coin: String, first: Long, end: Long,
        progress: (String) -> Unit
    ): List<SignalEngine.Bar> {
        val bars = ArrayList<SignalEngine.Bar>()
        var cursor = first
        var pages = 0
        while (cursor <= end && pages < MAX_PAGES) {
            val url = "https://api.binance.us/api/v3/klines?symbol=" +
                coin + "USDT&interval=1m&startTime=" + cursor +
                "&endTime=" + end + "&limit=1000"
            val body = client.newCall(Request.Builder().url(url).get().build())
                .execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error(
                        "Binance.US HTTP " + response.code + ": " + text.take(140))
                    if (text.isBlank()) error("Empty Binance.US history response")
                    text
                }
            val json = JSONArray(body)
            if (json.length() == 0) break
            for (i in 0 until json.length()) {
                val row = json.getJSONArray(i)
                val open = row.getDouble(1)
                val high = row.getDouble(2)
                val low = row.getDouble(3)
                val close = row.getDouble(4)
                if (open <= 0 || high <= 0 || low <= 0 || close <= 0) continue
                bars.add(SignalEngine.Bar(
                    row.getLong(0), row.getLong(6), open, high, low, close,
                    row.getDouble(7)
                ))
            }
            val last = json.getJSONArray(json.length() - 1).getLong(0)
            require(last >= cursor) { "Binance.US pagination did not advance" }
            cursor = last + MINUTE
            pages++
            if (pages % 3 == 0) {
                progress("Downloaded " + bars.size + " one-minute candles (" +
                    pages + " pages) for " + coin + "...")
            }
            // Some symbols have missing minute bars; a short page isn't proof
            // that all later candles have been fetched. Continue by timestamp.
        }
        if (cursor <= end) error(
            "History download incomplete: fetched " + pages +
                " pages, stopped before requested end. Cannot report partial sample."
        )
        progress("Download finished: " + bars.size + " minute candles. Replaying...")
        return bars.distinctBy { it.openedAt }.sortedBy { it.openedAt }
    }
}
