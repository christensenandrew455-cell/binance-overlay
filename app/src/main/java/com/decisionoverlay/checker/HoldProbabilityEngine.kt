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
 * Completely separate strategy: predict the currently LEADING side will HOLD.
 * Each 15m round contributes one observation at EVERY completed 1m candle,
 * 14 minutes remaining through 1 minute remaining. NO future candle is used
 * for the observation. Results are only Binance.US settlement proxies.
 */
object HoldProbabilityEngine {
    const val MAX_DISTANCE_BAND = 20
    const val MIN_MATCHES = 100
    const val TRAIN_ROUNDS = 1200
    const val TEST_ROUNDS = 1200
    const val TOTAL_ROUNDS = TRAIN_ROUNDS + TEST_ROUNDS
    const val MINUTE = 60_000L
    const val QUARTER = 15L * MINUTE
    const val CACHE_MILLIS = 4L * 60L * 60L * 1000L
    val coins = HistoricalSignalReplay.supportedCoins

    // BTC increments exactly $50. Other markets need meaningful smaller USD steps.
    // E.g. a $50 bin would group *all* DOGE, XRP and NEAR moves together.
    fun dollarStep(coin: String): Double = when (coin) {
        "BTC" -> 50.0
        "ETH", "BNB" -> 5.0
        "SOL" -> 0.5
        "XRP" -> 0.01
        "DOGE" -> 0.001
        "HYPE" -> 0.25
        "NEAR" -> 0.025
        "ZEC" -> 2.0
        else -> error("Unsupported coin " + coin)
    }

    data class Rate(var count: Int = 0, var successes: Int = 0) {
        fun record(won: Boolean) { count++; if (won) successes++ }
        val probability: Double get() = if (count == 0) Double.NaN
            else successes.toDouble() / count
        fun lower95(): Double {
            if (count == 0) return 0.0
            val p = successes.toDouble() / count
            val z = 1.96
            val denom = 1.0 + z * z / count
            val center = p + z * z / (2 * count)
            val margin = z * sqrt(p * (1 - p) / count +
                z * z / (4.0 * count * count))
            return max(0.0, (center - margin) / denom)
        }
        fun toJson() = JSONObject().put("n", count).put("w", successes)
        companion object {
            fun parse(j: JSONObject) = Rate(j.getInt("n"), j.getInt("w"))
        }
    }

    /** 1 = YES-leading; -1 = NO-leading. */
    data class Key(val minutesLeft: Int, val distanceBand: Int, val direction: Int) {
        fun serialize() = minutesLeft.toString() + ":" +
            distanceBand + ":" + direction
        companion object {
            fun parse(v: String): Key {
                val x = v.split(":").map { it.toInt() }
                require(x.size == 3)
                return Key(x[0], x[1], x[2])
            }
        }
    }

    data class Historical(
        val coin: String, val scanned: Int, val trainMarkets: Int,
        val testMarkets: Int, val skipped: Int, val calculatedAt: Long,
        val train: Map<Key, Rate>, val test: Map<Key, Rate>
    ) {
        fun rate(key: Key, testing: Boolean): Rate =
            (if (testing) test else train)[key] ?: Rate()

        fun report(): String = buildString {
            appendLine("STAY ABOVE / BELOW TARGET — " + coin)
            appendLine("Binance.US historical one-minute OHLC data; Kalshi index is different.")
            appendLine("Every completed 1m candle: 14, 13, ... 1 minutes remaining.")
            appendLine("Eligible markets: 10 AM–11 PM New York, DST-aware.")
            appendLine("BTC bands: $0–$50, $50–$100, etc. Other coins use scaled USD steps.")
            appendLine(String.format(Locale.US, "Distance step for %s = $%.6f", coin, dollarStep(coin)))
            appendLine("Older " + trainMarkets + " training markets; newer " +
                testMarkets + " held-out markets; missing " + skipped + ".")
            appendLine("YES wins ties. A NO lead must settle strictly below target.")
            appendLine("Sample n is per minute + distance + direction, not all combined.")
            appendLine("These are observational hold rates, not validated Kalshi probabilities.")
            appendLine()
            appendLine("=== HELD-OUT HOLD PROBABILITIES ===")
            appendLine("Minutes left | gap band | YES-leading holds | NO-leading holds | pooled")
            // Pooling both directions is a useful diagnostic, not a substitute for
            // direction-specific evidence when choosing an actual contract.
            for (m in 14 downTo 1) {
                appendLine("--- " + m + " MINUTES LEFT ---")
                for (band in 0..MAX_DISTANCE_BAND) {
                    val up = rate(Key(m, band, 1), true)
                    val down = rate(Key(m, band, -1), true)
                    val n = up.count + down.count
                    if (n == 0) continue
                    val pooled = Rate(n, up.successes + down.successes)
                    appendLine(String.format(Locale.US,
                        "%2dm | %-21s | YES %d/%d %s | NO %d/%d %s | ALL %d/%d %s",
                        m, bandLabel(coin, band),
                        up.successes, up.count, percent(up),
                        down.successes, down.count, percent(down),
                        pooled.successes, pooled.count, percent(pooled)))
                }
            }
            appendLine()
            appendLine("History calculated: " + Instant.ofEpochMilli(calculatedAt))
            appendLine("No historical Kalshi asks or fees: do not interpret hold rates as returns.")
        }

        fun toJson(): JSONObject {
            val obj = JSONObject().put("version", 1).put("coin", coin)
                .put("scanned", scanned).put("trainMarkets", trainMarkets)
                .put("testMarkets", testMarkets).put("skipped", skipped)
                .put("calculatedAt", calculatedAt)
            fun encode(map: Map<Key, Rate>): JSONObject {
                val o = JSONObject()
                map.forEach { (k, v) -> o.put(k.serialize(), v.toJson()) }
                return o
            }
            return obj.put("train", encode(train)).put("test", encode(test))
        }
        companion object {
            fun parse(j: JSONObject): Historical {
                require(j.getInt("version") == 1)
                fun decode(name: String): Map<Key, Rate> {
                    val o = j.getJSONObject(name)
                    return o.keys().asSequence().associate { key ->
                        Key.parse(key) to Rate.parse(o.getJSONObject(key))
                    }
                }
                return Historical(j.getString("coin"), j.getInt("scanned"),
                    j.getInt("trainMarkets"), j.getInt("testMarkets"),
                    j.getInt("skipped"), j.getLong("calculatedAt"),
                    decode("train"), decode("test"))
            }
        }
    }

    private fun percent(rate: Rate): String =
        if (rate.count == 0) "N/A"
        else String.format(Locale.US, "%.1f%%", 100 * rate.probability)

    fun bandFor(coin: String, absGap: Double): Int {
        require(absGap.isFinite() && absGap >= 0.0)
        return floor(absGap / dollarStep(coin)).toInt().coerceIn(0, MAX_DISTANCE_BAND)
    }
    fun bandLabel(coin: String, band: Int): String {
        val step = dollarStep(coin)
        return if (band >= MAX_DISTANCE_BAND)
            String.format(Locale.US, "$%.5f+", band * step)
        else String.format(Locale.US, "$%.5f–$%.5f", band * step, (band + 1) * step)
    }
    fun leadingSide(spot: Double, target: Double): Int = when {
        spot > target -> 1
        spot < target -> -1
        else -> 0 // Exactly at target is not a side with a lead.
    }
    fun held(lead: Int, settlement: Double, target: Double): Boolean =
        if (lead == 1) settlement >= target else lead == -1 && settlement < target
    fun breakEven(ask: Double): Double = ask + estimatedFee(ask)
    fun estimatedFee(ask: Double): Double {
        require(ask in 0.0..1.0)
        return ceil(0.07 * ask * (1 - ask) * 10000.0) / 10000.0
    }

    /** Conservative gate. We do not mistake a bare 70% > 60% for a 10% net edge. */
    data class Verdict(val label: String, val why: String, val evCents: Double,
        val lower95: Double)
    fun decide(training: Rate, testing: Rate, ask: Double?, minutes: Double): Verdict {
        fun wait(why: String) = Verdict("WAIT", why, Double.NaN, testing.lower95())
        if (minutes > 5.0 || minutes <= 3.0)
            return wait("Learning covers every minute, but entries remain in the 4–5-minute window.")
        if (ask == null || ask <= 0.0 || ask >= 1.0) return wait("No valid executable Kalshi ask.")
        if (training.count < MIN_MATCHES)
            return wait("Only " + training.count + "/100 older training matches for this exact band.")
        if (testing.count < MIN_MATCHES)
            return wait("Only " + testing.count + "/100 newer testing matches for this exact band.")
        // Two historical periods must support the advantage. Use lower confidence
        // bound, rather than simply the optimistic point estimate.
        val cost = breakEven(ask)
        val conservative = min(training.lower95(), testing.lower95())
        val evCents = (testing.probability - cost) * 100.0
        if (conservative <= cost + 0.02)
            return Verdict("WAIT", "Confidence-adjusted edge does not clear ask + estimated fee + 2c buffer.",
                evCents, conservative)
        return Verdict("POTENTIAL BUY", "Both time periods support an estimated hold advantage. Verify the live ask and market first.",
            evCents, conservative)
    }

    fun cached(context: Context, coin: String): Historical? {
        return try {
            val file = context.getFileStreamPath("stay_" + coin + ".json")
            if (!file.exists()) return null
            val j = JSONObject(file.readText())
            val historic = Historical.parse(j)
            if (historic.coin != coin || System.currentTimeMillis() - historic.calculatedAt > CACHE_MILLIS)
                null else historic
        } catch (_: Exception) { null }
    }
    fun load(context: Context, coin: String, progress: (String) -> Unit): Historical {
        require(coin in coins)
        val existing = cached(context, coin)
        if (existing != null) { progress(coin + ": using saved historical results."); return existing }
        val fresh = scan(coin, progress)
        context.openFileOutput("stay_" + coin + ".json", Context.MODE_PRIVATE).use {
            it.write(fresh.toJson().toString().toByteArray(Charsets.UTF_8))
        }
        return fresh
    }

    private fun scan(coin: String, progress: (String) -> Unit): Historical {
        val client = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
        val completedEnd = (System.currentTimeMillis() / QUARTER) * QUARTER
        val starts = HistoricalSignalReplay.lastEligibleStarts(completedEnd, TOTAL_ROUNDS)
        val training = HashMap<Key, Rate>()
        val testing = HashMap<Key, Rate>()
        var trainingValid = 0
        var testingValid = 0
        var skipped = 0
        var processed = 0

        // Only download candles during 10 AM–11 PM New York market sessions,
        // rather than the 11 unused hours each day.
        val sessions = mutableListOf<MutableList<Long>>()
        for (start in starts) {
            val lastSession = sessions.lastOrNull()
            if (lastSession != null && lastSession.last() + QUARTER == start) {
                lastSession.add(start)
            } else {
                sessions.add(mutableListOf(start))
            }
        }

        for ((sessionIndex, session) in sessions.withIndex()) {
            val first = session.first() - MINUTE
            val until = session.last() + 14 * MINUTE + MINUTE - 1
            val bars = fetchMinuteProxies(client, coin, first, until)
            for (start in session) {
                val marketIndex = starts.binarySearch(start)
                val target = bars[start - MINUTE]
                val end = bars[start + 14 * MINUTE]
                if (target == null || end == null || target <= 0.0 ||
                    (0..14).any { bars[start + it * MINUTE] == null }) {
                    skipped++
                    continue
                }
                val bucket = if (marketIndex < TRAIN_ROUNDS) training else testing
                if (marketIndex < TRAIN_ROUNDS) trainingValid++ else testingValid++
                for (minutes in 14 downTo 1) {
                    // At t = roundStart + (15 - minutes) minutes, the most recent
                    // completed candle is the minute ending immediately before t.
                    val observedMinuteStart = start + (14 - minutes) * MINUTE
                    val entry = bars[observedMinuteStart] ?: continue
                    val direction = leadingSide(entry, target)
                    if (direction == 0) continue
                    val key = Key(minutes, bandFor(coin, abs(entry - target)), direction)
                    bucket.getOrPut(key) { Rate() }.record(held(direction, end, target))
                }
                processed++
            }
            if (sessionIndex % 4 == 0 || sessionIndex == sessions.lastIndex) {
                progress(String.format(Locale.US,
                    "%s: %d/%d historical markets, %d/%d sessions; %d missing",
                    coin, processed, TOTAL_ROUNDS, sessionIndex + 1, sessions.size, skipped))
            }
        }
        require(trainingValid + testingValid >= 300) {
            "Too few complete Binance.US markets for " + coin +
                " (" + (trainingValid + testingValid) + ")"
        }
        return Historical(coin, TOTAL_ROUNDS, trainingValid, testingValid, skipped,
            System.currentTimeMillis(), training, testing)
    }

    private fun fetchMinuteProxies(
        client: OkHttpClient, coin: String, from: Long, end: Long
    ): Map<Long, Double> {
        val bars = HashMap<Long, Double>()
        var cursor = from
        var pages = 0
        while (cursor <= end && pages < 3) {
            val url = "https://api.binance.us/api/v3/klines?symbol=" + coin +
                "USDT&interval=1m&startTime=" + cursor + "&endTime=" + end + "&limit=1000"
            val responseBody = client.newCall(Request.Builder().url(url).get().build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error("Binance.US " + coin +
                        " HTTP " + response.code + ": " + body.take(120))
                    body
                }
            val rows = JSONArray(responseBody)
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val c = rows.getJSONArray(i)
                val ts = c.getLong(0)
                val price = (c.getDouble(1) + c.getDouble(2) +
                    c.getDouble(3) + c.getDouble(4)) / 4.0
                if (price > 0 && price.isFinite()) bars[ts] = price
            }
            val last = rows.getJSONArray(rows.length() - 1).getLong(0)
            require(last >= cursor) { "Binance historical pagination stuck" }
            cursor = last + MINUTE
            pages++
        }
        // If a market has missing candles we skip it; never fabricate a value.
        return bars
    }
}
