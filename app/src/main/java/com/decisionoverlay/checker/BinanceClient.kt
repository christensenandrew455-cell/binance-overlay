package com.decisionoverlay.checker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class BinanceClient(private val http: OkHttpClient) {
    @Volatile
    var activeRestHost: String = REST_HOSTS.first()

    @Volatile
    var apiKey: String = ""

    fun fetchKlines(symbol: String, interval: String, limit: Int = 150): KlineFetch {
        val notes = ArrayList<String>()
        val hosts = LinkedHashSet<String>().apply {
            add(activeRestHost)
            REST_HOSTS.forEach { add(it) }
        }
        for (host in hosts) {
            try {
                val url = "$host/api/v3/klines?symbol=$symbol&interval=$interval&limit=$limit"
                val builder = Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "DecisionOverlay/1.0")
                val key = apiKey.trim()
                if (key.isNotEmpty()) builder.header("X-MBX-APIKEY", key)
                val call = http.newCall(builder.build())
                call.timeout().timeout(20, TimeUnit.SECONDS)
                call.execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        notes += "$host HTTP ${response.code}: ${body.take(180)}"
                    } else if (!body.trimStart().startsWith("[")) {
                        notes += "$host unexpected body: ${body.take(180)}"
                    } else {
                        activeRestHost = host
                        return KlineFetch(parseKlines(body), host, notes.toList())
                    }
                }
            } catch (error: Exception) {
                notes += "$host ${error.javaClass.simpleName}: ${error.message ?: "no message"}"
            }
        }
        throw IllegalStateException(notes.joinToString(" | ").ifBlank { "All Binance REST hosts failed" })
    }

    private fun parseKlines(body: String): List<Candle> {
        val array = JSONArray(body)
        val now = System.currentTimeMillis()
        val candles = ArrayList<Candle>(array.length())
        for (index in 0 until array.length()) {
            val kline = array.getJSONArray(index)
            val closeTime = kline.getLong(6)
            candles += Candle(
                openTime = kline.getLong(0),
                open = kline.getString(1).toDouble(),
                high = kline.getString(2).toDouble(),
                low = kline.getString(3).toDouble(),
                close = kline.getString(4).toDouble(),
                volume = kline.getString(5).toDouble(),
                closeTime = closeTime,
                closed = closeTime < now,
            )
        }
        return candles
    }

    companion object {
        /** Public Binance market-data hosts. `api.binance.com` often returns HTTP 451, so the vision host is first. Set BINANCE_API_BASE_URL to prepend another https host. */
        val REST_HOSTS: List<String> = buildList {
            val override = BuildConfig.BINANCE_API_BASE_URL.trim().trimEnd('/')
            if (override.startsWith("https://")) add(override)
            add("https://data-api.binance.vision")
            add("https://api.binance.com")
            add("https://api1.binance.com")
            add("https://api2.binance.com")
            add("https://api3.binance.com")
        }.distinct()
    }
}

data class KlineFetch(
    val candles: List<Candle>,
    val host: String,
    val notes: List<String>,
)
