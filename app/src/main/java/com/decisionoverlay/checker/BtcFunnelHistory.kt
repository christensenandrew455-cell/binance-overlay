package com.decisionoverlay.checker

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor

/**
 * BTC-only 0.01 percentage-point historical calibration.
 * Observations: 15m left uses first candle OPEN; 14..1 left uses
 * most recent COMPLETED 1-minute candle CLOSE.
 * Target: final close BEFORE market open. Settlement proxy: final close.
 * All 4,800 market starts are in 10:00–23:00 America/New_York (DST-aware).
 * Older 2,400 markets choose a line; newer 2,400 validate it.
 * This is NOT Kalshi's CF Benchmarks settlement index.
 */
object BtcFunnelHistory {
    private const val CACHE_NAME = "btc_funnel_fine_history_v1.json"
    private const val VALID_MILLIS = 24L * 60L * 60L * 1000L
    private const val MINUTE = 60_000L
    private const val QUARTER = 15L * MINUTE
    private const val TRAIN = 2400
    private const val TOTAL = 4800

    private data class Candle(val open: Double, val close: Double)

    fun cached(context: Context): HoldProbabilityEngine.Historical? = try {
        val file = context.getFileStreamPath(CACHE_NAME)
        if (!file.exists()) null
        else HoldProbabilityEngine.Historical.parse(JSONObject(file.readText()))
            .takeIf { it.coin == "BTC" &&
                System.currentTimeMillis() - it.calculatedAt in 0..VALID_MILLIS }
    } catch (_: Exception) { null }

    fun load(context: Context, onProgress: (String) -> Unit,
        shouldCancel: () -> Boolean = { false }): HoldProbabilityEngine.Historical {
        cached(context)?.let { onProgress("BTC fine-resolution history loaded from cache"); return it }
        val fresh = scan(onProgress, shouldCancel)
        check(!shouldCancel()) { "Calibration canceled" }
        context.openFileOutput(CACHE_NAME, Context.MODE_PRIVATE).use {
            it.write(fresh.json().toString().toByteArray(Charsets.UTF_8))
        }
        return fresh
    }

    private fun scan(progress: (String) -> Unit,
        canceled: () -> Boolean): HoldProbabilityEngine.Historical {
        val http = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS).build()
        val starts = HistoricalSignalReplay.lastEligibleStarts(
            System.currentTimeMillis() / QUARTER * QUARTER, TOTAL)
        val old = HashMap<HoldProbabilityEngine.Key, HoldProbabilityEngine.Rate>()
        val newer = HashMap<HoldProbabilityEngine.Key, HoldProbabilityEngine.Rate>()
        val sessions = mutableListOf<MutableList<Long>>()
        for (start in starts) {
            if (sessions.isNotEmpty() && sessions.last().last() + QUARTER == start)
                sessions.last().add(start)
            else sessions.add(mutableListOf(start))
        }
        var trainMarkets = 0
        var testMarkets = 0
        var missing = 0
        for ((sessionNumber, session) in sessions.withIndex()) {
            check(!canceled()) { "Calibration canceled" }
            // Adjacent sessions grouped to avoid 15 separate API calls per market.
            val candles = download(http, session.first() - MINUTE,
                session.last() + 15L * MINUTE - 1L, canceled)
            for (start in session) {
                val index = starts.binarySearch(start)
                val target = candles[start - MINUTE]?.close
                val end = candles[start + 14L * MINUTE]?.close
                if (target == null || end == null || target <= 0 ||
                    (0..14).any { candles[start + it * MINUTE] == null }) {
                    missing++
                    continue
                }
                val dataset = if (index < TRAIN) old else newer
                if (index < TRAIN) trainMarkets++ else testMarkets++
                for (left in 15 downTo 1) {
                    val spot = if (left == 15) candles[start]?.open
                    else candles[start + (14 - left) * MINUTE]?.close
                    if (spot == null || spot <= 0) continue
                    val side = when {
                        spot > target -> 1
                        spot < target -> -1
                        else -> 0
                    }
                    if (side == 0) continue // 50% target is a reference, not a trained direction
                    val gap = abs((spot - target) / target * 100.0)
                    // Keep the entire range the outer funnel can use.
                    val band = floor((gap + 1e-9) / BtcFunnelLevels.SAMPLE_STEP_PCT).toInt()
                    if (band !in 0..149) continue
                    val key = HoldProbabilityEngine.Key(left, band, side)
                    val held = if (side == 1) end >= target else end < target
                    dataset.getOrPut(key) { HoldProbabilityEngine.Rate() }.record(held)
                }
            }
            if (sessionNumber % 4 == 0 || sessionNumber == sessions.lastIndex)
                progress("BTC funnel history: day group " + (sessionNumber + 1) +
                    "/" + sessions.size + ", markets " + (trainMarkets + testMarkets) +
                    "/" + TOTAL + ", missing " + missing)
        }
        require(trainMarkets >= 1000 && testMarkets >= 1000) {
            "Insufficient complete BTC market history; no probability levels available"
        }
        return HoldProbabilityEngine.Historical("BTC", starts.size,
            trainMarkets, testMarkets, missing, System.currentTimeMillis(), old, newer)
    }

    private fun download(http: OkHttpClient, first: Long, last: Long,
        canceled: () -> Boolean): Map<Long, Candle> {
        val result = HashMap<Long, Candle>()
        var next = first
        while (next <= last) {
            check(!canceled()) { "Calibration canceled" }
            val url = "https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
                "&interval=1m&startTime=" + next + "&endTime=" + last + "&limit=1000"
            val rows = http.newCall(Request.Builder().url(url).get().build())
                .execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful)
                        error("Binance.US HTTP " + response.code + ": " + body.take(100))
                    JSONArray(body)
                }
            if (rows.length() == 0) break
            for (i in 0 until rows.length()) {
                val c = rows.getJSONArray(i)
                val open = c.getDouble(1)
                val close = c.getDouble(4)
                if (open > 0 && close > 0)
                    result[c.getLong(0)] = Candle(open, close)
            }
            val prev = next
            next = rows.getJSONArray(rows.length() - 1).getLong(0) + MINUTE
            check(next > prev) { "Binance.US candle pagination stalled" }
        }
        return result
    }
}
