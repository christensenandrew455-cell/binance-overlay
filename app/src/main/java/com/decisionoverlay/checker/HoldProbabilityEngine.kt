package com.decisionoverlay.checker

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Independent stay-on-current-side research engine.
 *
 * All NINE coins share the same percentage-distance buckets. Every 15-minute
 * market is observed at 15 (opening tick), 14, ... 1 whole minutes left.
 * Historical target/settlement values are Binance.US OHLC4 approximations,
 * NOT the CF Benchmarks index used for Kalshi settlement.
 *
 * Historical training is chronologically older than validation. No point in
 * time ever sees a candle not yet closed (15m opening uses its OPEN only).
 */
object HoldProbabilityEngine {
    const val MAX_DISTANCE_BAND = 30
    const val PERCENT_STEP = 0.05 // percentage points, not dollars (5 basis points)
    const val MIN_MATCHES = 100
    const val TABLE_MIN_MATCHES = 40 // exploratory table ONLY, never live buy gating
    const val TRAIN_ROUNDS = 1200
    const val TEST_ROUNDS = 1200
    const val TOTAL_ROUNDS = TRAIN_ROUNDS + TEST_ROUNDS
    const val MINUTE = 60_000L
    const val QUARTER = 15L * MINUTE
    const val CACHE_MILLIS = 4L * 60L * 60L * 1000L
    const val EDGE_BUFFER = 0.02 // $0.02 per winning $1 contract, beyond fees
    val coins = HistoricalSignalReplay.supportedCoins

    data class Rate(var count: Int = 0, var successes: Int = 0) {
        fun record(won: Boolean) { count++; if (won) successes++ }
        val probability: Double get() =
            if (count == 0) Double.NaN else successes.toDouble() / count
        fun lower95(): Double {
            if (count == 0) return 0.0
            val p = successes.toDouble() / count
            val z = 1.96
            val den = 1.0 + z * z / count
            return max(0.0, (p + z * z / (2.0 * count) -
                z * sqrt(p * (1 - p) / count + z * z / (4.0 * count * count))) / den)
        }
        fun json() = JSONObject().put("n", count).put("w", successes)
        companion object {
            fun parse(j: JSONObject) = Rate(j.getInt("n"), j.getInt("w"))
        }
    }

    // trend: 0 = all historical cases, 1 = 3m trend agrees with lead,
    // -1 = 3m trend opposes lead. Uses ONLY previously closed 1m candles.
    data class Key(val minutesLeft: Int, val distanceBand: Int,
        val direction: Int, val trend: Int = 0) {
        fun encode() = listOf(minutesLeft, distanceBand, direction, trend).joinToString(":")
        companion object {
            fun parse(s: String): Key {
                val n = s.split(":").map { it.toInt() }
                require(n.size == 4)
                return Key(n[0], n[1], n[2], n[3])
            }
        }
    }

    data class Historical(
        val coin: String, val scanned: Int, val trainMarkets: Int,
        val testMarkets: Int, val skipped: Int, val calculatedAt: Long,
        val train: Map<Key, Rate>, val test: Map<Key, Rate>
    ) {
        fun rate(key: Key, newer: Boolean): Rate =
            (if (newer) test else train)[key] ?: Rate()

        /** A minimum gap is quoted only when BOTH periods have >=100 samples
         * in that exact % bucket and their 95% lower bounds pass the live cost.
         * Not a prediction that the threshold holds at any other ask price.
         */
        fun threshold(minute: Int, side: Int, ask: Double, trend: Int = 0): Double? {
            if (minute !in 1..15 || side !in listOf(-1, 1) ||
                ask <= 0 || ask >= 1) return null
            for (band in 0 until MAX_DISTANCE_BAND) {
                val key = Key(minute, band, side, trend)
                if (decide(rate(key, false), rate(key, true), ask, minute.toDouble())
                        .label == "POTENTIAL BUY")
                    return band * PERCENT_STEP
            }
            return null
        }

        /**
         * Research-only percentage target, NOT a Kalshi contract quote or buy rule.
         * Pick the smallest leading-price gap using ONLY older training observations.
         * Verify its actual stay-side rate on the newer, untouched period.
         *
         * Aggregate all distance buckets >= the candidate gap so estimates
         * do not hinge on one tiny 0.05%-wide bucket. Both periods need enough
         * examples; if not, leave the result unknown instead of making it up.
         */
        data class ChanceGap(val minimumGapPct: Double,
            val training: Rate, val newer: Rate)

        fun chanceGap(minute: Int, side: Int, desiredStay: Double): ChanceGap? {
            if (minute !in 1..15 || side !in listOf(-1, 1) ||
                desiredStay !in 0.0..1.0) return null
            val older = Rate()
            val newer = Rate()
            var qualifying: ChanceGap? = null
            for (band in MAX_DISTANCE_BAND downTo 0) {
                val a = rate(Key(minute, band, side), false)
                val b = rate(Key(minute, band, side), true)
                older.count += a.count
                older.successes += a.successes
                newer.count += b.count
                newer.successes += b.successes
                if (older.count >= TABLE_MIN_MATCHES &&
                    newer.count >= TABLE_MIN_MATCHES &&
                    older.probability >= desiredStay) {
                    // Looping from far to near, the last qualifying gap is
                    // the SMALLEST gap supported by older training data.
                    qualifying = ChanceGap(band * PERCENT_STEP,
                        older.copy(), newer.copy())
                }
            }
            return qualifying
        }

        private fun chanceCell(minute: Int, side: Int, desiredStay: Double): String {
            val found = chanceGap(minute, side, desiredStay) ?: return "—"
            return String.format(Locale.US, ">=%.2f%% -> %.1f%% (n=%d)",
                found.minimumGapPct, found.newer.probability * 100.0,
                found.newer.count)
        }

        /** Compact, copyable output: 15 through 1 minutes; YES and NO;
         * four 60%-90% desired historical stay-side rates for each coin.
         */
        fun chanceTable(desiredStay: Double): String = buildString {
            appendLine(String.format(Locale.US,
                "%s — desired stay %.0f%% (training-derived gap, newer measured rate)",
                coin, desiredStay * 100.0))
            appendLine("Left | YES: price gap -> held-out stay | NO: price gap -> held-out stay")
            for (minute in 15 downTo 1) {
                appendLine(String.format(Locale.US, "%2dm | %s | %s",
                    minute, chanceCell(minute, 1, desiredStay),
                    chanceCell(minute, -1, desiredStay)))
            }
            appendLine("— = insufficient evidence. n = newer cases with gap at least shown.")
            appendLine("Observation, NOT a calibrated forecast or a Kalshi contract price.")
        }

        fun compactChanceReport(): String = buildString {
            appendLine("STAY-SIDE PRICE GAP (%) — " + coin)
            appendLine("15 to 1 min left; YES above target / NO below target.")
            appendLine("Training selects distance; newer held-out markets measure stays.")
            appendLine("Only 10 AM–11 PM New York, 1200 older + 1200 newer markets.")
            appendLine("At least " + TABLE_MIN_MATCHES + " samples in EACH period per threshold.")
            appendLine("A 90% goal may validate BELOW 90%; see actual newer result.")
            appendLine("Binance.US historical proxy, not Kalshi settlement/index.")
            appendLine()
            for (desired in listOf(.60, .70, .80, .90)) {
                appendLine(chanceTable(desired))
                appendLine()
            }
        }

        fun report(): String = buildString {
            appendLine("STAY-SIDE PERCENTAGE BACKTEST — " + coin)
            appendLine("15m OPEN sample + 14, 13, ... 1 minutes left; NO future prices.")
            appendLine("10 AM–11 PM New York only, DST-aware. N=" + scanned +
                " eligible markets (" + trainMarkets + " older / " + testMarkets +
                " newer, " + skipped + " missing).")
            appendLine(String.format(Locale.US,
                "All coins use %.2f%%-wide distance bands from target. Last bin >=%.2f%%.",
                PERCENT_STEP, MAX_DISTANCE_BAND * PERCENT_STEP))
            appendLine("15m observation uses first candle OPEN, never its future close.")
            appendLine("Trend = last three completed 1m candles; aligned or opposed.")
            appendLine("YES wins ties. NO requires a strictly below-target settlement.")
            appendLine("Binance.US target/end OHLC4 are NOT Kalshi CF Benchmarks index prices.")
            appendLine("These are historical observations, NOT calibrated trading probabilities.")
            appendLine()
            appendLine("=== 60c KALSHI ASK SCENARIO — MIN GAP BY MINUTE ===")
            appendLine("Illustration only: actual live YES and NO asks may differ from 60c.")
            appendLine("Threshold only if >=100 cases in EACH split and 95% lower bounds")
            appendLine("both beat quoted ask + 1-contract estimated taker fee + 2c buffer.")
            appendLine("Time left | YES requires >= gap | NO requires >= gap | 3m-trend YES / NO")
            for (m in 15 downTo 1) {
                appendLine(String.format(Locale.US, "%2dm | %-14s | %-14s | %s / %s",
                    m, gapLabel(threshold(m, 1, .60)),
                    gapLabel(threshold(m, -1, .60)),
                    gapLabel(threshold(m, 1, .60, 1)),
                    gapLabel(threshold(m, -1, .60, 1))))
            }
            appendLine()
            appendLine("=== ALL HELD-OUT PERCENTAGE BUCKETS (n per minute, side, gap) ===")
            for (m in 15 downTo 1) {
                appendLine("--- " + m + " MINUTES LEFT ---")
                for (b in 0..MAX_DISTANCE_BAND) {
                    val yes = rate(Key(m, b, 1), true)
                    val no = rate(Key(m, b, -1), true)
                    if (yes.count + no.count == 0) continue
                    appendLine(String.format(Locale.US,
                        "%2dm | %-16s | YES %d/%d %s | NO %d/%d %s",
                        m, bandLabel(b), yes.successes, yes.count, pct(yes),
                        no.successes, no.count, pct(no)))
                }
            }
            appendLine()
            appendLine("Past Kalshi asks/spreads are NOT available here; this is not a")
            appendLine("profitable-strategy backtest. Compare LIVE quoted asks and fees.")
            appendLine("Calculated " + Instant.ofEpochMilli(calculatedAt))
        }

        fun json(): JSONObject {
            fun encode(source: Map<Key, Rate>): JSONObject {
                val o = JSONObject()
                source.forEach { (k, v) -> o.put(k.encode(), v.json()) }
                return o
            }
            return JSONObject().put("version", 2).put("coin", coin)
                .put("scanned", scanned).put("trainMarkets", trainMarkets)
                .put("testMarkets", testMarkets).put("skipped", skipped)
                .put("calculatedAt", calculatedAt)
                .put("train", encode(train)).put("test", encode(test))
        }
        companion object {
            fun parse(j: JSONObject): Historical {
                require(j.getInt("version") == 2)
                fun decode(name: String): Map<Key, Rate> {
                    val o = j.getJSONObject(name)
                    return o.keys().asSequence().associate {
                        Key.parse(it) to Rate.parse(o.getJSONObject(it))
                    }
                }
                return Historical(j.getString("coin"), j.getInt("scanned"),
                    j.getInt("trainMarkets"), j.getInt("testMarkets"),
                    j.getInt("skipped"), j.getLong("calculatedAt"),
                    decode("train"), decode("test"))
            }
        }
    }

    private data class Candle(val open: Double, val proxy: Double)

    fun distancePercent(spot: Double, target: Double): Double {
        require(spot.isFinite() && target.isFinite() && spot > 0 && target > 0)
        return abs((spot - target) / target) * 100.0
    }
    fun bandForPercent(percentGap: Double): Int {
        require(percentGap.isFinite() && percentGap >= 0)
        // Tiny epsilon prevents 0.10% from slipping into 0.05-0.10% due to floating-point roundoff.
        return floor((percentGap + 1e-9) / PERCENT_STEP).toInt().coerceIn(0, MAX_DISTANCE_BAND)
    }
    fun bandLabel(band: Int): String =
        if (band >= MAX_DISTANCE_BAND)
            String.format(Locale.US, ">=%.2f%%", MAX_DISTANCE_BAND * PERCENT_STEP)
        else String.format(Locale.US, "%.2f–%.2f%%",
            band * PERCENT_STEP, (band + 1) * PERCENT_STEP)

    private fun gapLabel(p: Double?): String =
        if (p == null) "NO DATA"
        else String.format(Locale.US, ">=%.2f%%", p)

    private fun pct(rate: Rate): String =
        if (rate.count == 0) "N/A"
        else String.format(Locale.US, "%.1f%%", rate.probability * 100)

    fun leadingSide(spot: Double, target: Double): Int =
        when { spot > target -> 1; spot < target -> -1; else -> 0 }
    fun held(side: Int, end: Double, target: Double): Boolean =
        if (side == 1) end >= target else side == -1 && end < target

    /** Fee schedule July 2026: ceil to 1 centicent for direct, retail
     * cash rounded to cents; use conservative per-one-contract cent rounding.
     * May differ for fee-exempt/discounted series or large orders.
     */
    fun estimatedFee(ask: Double): Double {
        require(ask in 0.0..1.0)
        return ceil(0.07 * ask * (1.0 - ask) * 100.0) / 100.0
    }
    fun breakEven(ask: Double) = ask + estimatedFee(ask)

    data class Verdict(val label: String, val why: String,
        val evCents: Double, val lower95: Double)
    fun decide(training: Rate, testing: Rate, ask: Double?, minutes: Double): Verdict {
        fun wait(s: String) = Verdict("WAIT", s, Double.NaN,
            min(training.lower95(), testing.lower95()))
        if (minutes <= 0 || minutes > 15.0)
            return wait("Outside active 15-minute contract.")
        if (ask == null || !ask.isFinite() || ask <= 0.0 || ask >= 1.0)
            return wait("No executable Kalshi ask.")
        if (training.count < MIN_MATCHES)
            return wait("Need 100 training examples for this exact minute, gap and side; got " +
                training.count + ".")
        if (testing.count < MIN_MATCHES)
            return wait("Need 100 newer validation examples; got " + testing.count + ".")
        val price = breakEven(ask)
        val conservative = min(training.lower95(), testing.lower95())
        val ev = (testing.probability - price) * 100.0
        return if (conservative > price + EDGE_BUFFER)
            Verdict("POTENTIAL BUY",
                "Both independent periods clear the ask + estimated fee + 2c margin.",
                ev, conservative)
        else Verdict("WAIT",
            "Evidence does not beat ask + estimated fee + 2c margin.",
            ev, conservative)
    }

    fun cached(context: Context, coin: String): Historical? = try {
        val file = context.getFileStreamPath("stay_pct_v2_" + coin + ".json")
        if (!file.exists()) null
        else Historical.parse(JSONObject(file.readText())).takeIf {
            it.coin == coin && System.currentTimeMillis() - it.calculatedAt < CACHE_MILLIS
        }
    } catch (_: Exception) { null }

    fun load(context: Context, coin: String, progress: (String) -> Unit): Historical {
        require(coin in coins)
        cached(context, coin)?.let { progress(coin + ": cached percentage data"); return it }
        val data = scan(coin, progress)
        context.openFileOutput("stay_pct_v2_" + coin + ".json", Context.MODE_PRIVATE).use {
            it.write(data.json().toString().toByteArray(Charsets.UTF_8))
        }
        return data
    }

    private fun scan(coin: String, progress: (String) -> Unit): Historical {
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val end = (System.currentTimeMillis() / QUARTER) * QUARTER
        val starts = HistoricalSignalReplay.lastEligibleStarts(end, TOTAL_ROUNDS)
        val training = HashMap<Key, Rate>()
        val testing = HashMap<Key, Rate>()
        var validTrain = 0
        var validTest = 0
        var missing = 0

        // Collect per-day sessions so that 11 non-trading hours are NOT fetched.
        val sessions = mutableListOf<MutableList<Long>>()
        for (start in starts) {
            if (sessions.isNotEmpty() && sessions.last().last() + QUARTER == start)
                sessions.last().add(start)
            else sessions.add(mutableListOf(start))
        }
        for ((sessionIndex, session) in sessions.withIndex()) {
            // 4m extra history for the causal 3m trend.
            val candles = fetchMinuteProxies(client, coin,
                session.first() - 4 * MINUTE,
                session.last() + 15 * MINUTE - 1)
            for (start in session) {
                val index = starts.binarySearch(start)
                val target = candles[start - MINUTE]?.proxy
                val settlement = candles[start + 14 * MINUTE]?.proxy
                if (target == null || settlement == null || target <= 0 ||
                    (0..14).any { candles[start + it * MINUTE] == null }) {
                    missing++
                    continue
                }
                val bucket = if (index < TRAIN_ROUNDS) training else testing
                if (index < TRAIN_ROUNDS) validTrain++ else validTest++
                for (minute in 15 downTo 1) {
                    val observedAt = start + (15 - minute) * MINUTE
                    // Exactly at 15m, only the opening tick of first 1m candle
                    // exists. Later slots see only the most recently CLOSED 1m.
                    val spot = if (minute == 15) candles[start]?.open
                               else candles[observedAt - MINUTE]?.proxy
                    if (spot == null || spot <= 0) continue
                    val direction = leadingSide(spot, target)
                    if (direction == 0) continue
                    val band = bandForPercent(distancePercent(spot, target))
                    val won = held(direction, settlement, target)
                    bucket.getOrPut(Key(minute, band, direction, 0)) { Rate() }
                        .record(won)
                    val before = candles[observedAt - 4 * MINUTE]?.proxy
                    val latest = candles[observedAt - MINUTE]?.proxy
                    if (before != null && latest != null && latest != before) {
                        val aligned = if (leadingSide(latest, before) == direction) 1 else -1
                        bucket.getOrPut(Key(minute, band, direction, aligned)) { Rate() }
                            .record(won)
                    }
                }
            }
            if (sessionIndex % 5 == 0 || sessionIndex == sessions.lastIndex) {
                progress(coin + " percentage scan: session " +
                    (sessionIndex + 1) + "/" + sessions.size +
                    ", valid " + (validTrain + validTest) + "/" + TOTAL_ROUNDS +
                    ", missing " + missing)
            }
        }
        require(validTrain + validTest >= 300) {
            "Not enough complete Binance.US rounds (" + (validTrain + validTest) + ")"
        }
        return Historical(coin, TOTAL_ROUNDS, validTrain, validTest, missing,
            System.currentTimeMillis(), training, testing)
    }

    private fun fetchMinuteProxies(
        client: OkHttpClient, coin: String, start: Long, until: Long
    ): Map<Long, Candle> {
        val candles = HashMap<Long, Candle>()
        var cursor = start
        var pages = 0
        while (cursor <= until && pages < 3) {
            val url = "https://api.binance.us/api/v3/klines?symbol=" + coin +
                "USDT&interval=1m&startTime=" + cursor +
                "&endTime=" + until + "&limit=1000"
            val body = client.newCall(Request.Builder().url(url).get().build())
                .execute().use {
                    val value = it.body?.string().orEmpty()
                    if (!it.isSuccessful) error("Binance.US " + coin +
                        " HTTP " + it.code + ": " + value.take(100))
                    value
                }
            val rows = JSONArray(body)
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val r = rows.getJSONArray(i)
                val o = r.getDouble(1)
                val h = r.getDouble(2)
                val l = r.getDouble(3)
                val c = r.getDouble(4)
                if (o > 0 && h > 0 && l > 0 && c > 0)
                    candles[r.getLong(0)] = Candle(o, (o + h + l + c) / 4.0)
            }
            val last = rows.getJSONArray(rows.length() - 1).getLong(0)
            require(last >= cursor) { "Binance pagination not advancing" }
            cursor = last + MINUTE
            pages++
        }
        // No interpolation: incomplete markets are skipped.
        return candles
    }
}
