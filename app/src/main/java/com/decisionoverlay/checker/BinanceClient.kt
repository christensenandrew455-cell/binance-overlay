package com.decisionoverlay.checker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Reads public Binance spot candles. These read-only market-data endpoints do not need
 * a Binance account, API key, Vercel variable, or trading permission.
 */
class BinanceClient(private val http: OkHttpClient) {
    @Volatile
    var activeRestHost: String = REST_HOSTS.first()
        private set

    fun fetchKlines(symbol: String, interval: String, limit: Int = 150): KlineFetch {
        val notes = ArrayList<String>()
        val hosts = LinkedHashSet<String>().apply {
            add(activeRestHost)
            REST_HOSTS.forEach { add(it) }
        }

        for (host in hosts) {
            try {
                val url = "$host/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit"
                val request = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "DecisionOverlay/1.1")
                    .build()

                http.newCall(request).also { call ->
                    call.timeout().timeout(20, TimeUnit.SECONDS)
                }.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        notes += "$host HTTP " + response.code + ": " + body.take(180)
                    } else if (!body.trimStart().startsWith("[")) {
                        notes += "$host returned an unexpected response: " + body.take(180)
                    } else {
                        val parsed = parseKlines(body)
                        if (parsed.isEmpty()) {
                            notes += "$host returned zero candles"
                        } else {
                            activeRestHost = host
                            return KlineFetch(parsed, host, notes.toList())
                        }
                    }
                }
            } catch (error: Exception) {
                notes += "$host " + error.javaClass.simpleName + ": " + (error.message ?: "no message")
            }
        }

        throw IllegalStateException(notes.joinToString(" | ").ifBlank { "All Binance public REST hosts failed" })
    }

    private fun parseKlines(body: String): List<Candle> {
        val array = JSONArray(body)
        val now = System.currentTimeMillis()
        val candles = ArrayList<Candle>(array.length())

        for (index in 0 until array.length()) {
            val kline = array.getJSONArray(index)
            val open = kline.getString(1).toDouble()
            val high = kline.getString(2).toDouble()
            val low = kline.getString(3).toDouble()
            val close = kline.getString(4).toDouble()
            val volume = kline.getString(5).toDouble()
            if (!open.isFinite() || !high.isFinite() || !low.isFinite() ||
                !close.isFinite() || !volume.isFinite()
            ) continue

            val closeTime = kline.getLong(6)
            candles += Candle(
                openTime = kline.getLong(0),
                open = open,
                high = high,
                low = low,
                close = close,
                volume = volume,
                closeTime = closeTime,
                closed = closeTime < now,
            )
        }

        return candles
    }

    companion object {
        /** Public read-only market-data hosts, tried in order with fallback on network/HTTP errors. */
        val REST_HOSTS = listOf(
            "https://api.binance.us",
            "https://api1.binance.us",
            "https://api2.binance.us",
        )
    }
}

data class KlineFetch(
    val candles: List<Candle>,
    val host: String,
    val notes: List<String>,
)
