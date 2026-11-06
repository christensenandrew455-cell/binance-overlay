package com.decisionoverlay.checker

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class BinanceClient(private val http: OkHttpClient) {
    @Volatile
    var activeRestHost: String = REST_HOSTS.first()

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
                    .header("User-Agent", "DecisionOverlay/1.0")
                    .build()
                val call = http.newCall(request)
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

    fun parseKlineEvent(text: String, expectedSymbol: String): Candle? {
        val root = JSONObject(text)
        val data = if (root.has("k")) root else root.optJSONObject("data") ?: return null
        val kline = data.optJSONObject("k") ?: return null
        if (kline.optString("i") != "5m") return null
        val eventSymbol = kline.optString("s")
        if (eventSymbol.isNotBlank() && !eventSymbol.equals(expectedSymbol, ignoreCase = true)) return null
        return Candle(
            openTime = kline.getLong("t"),
            open = kline.getString("o").toDouble(),
            high = kline.getString("h").toDouble(),
            low = kline.getString("l").toDouble(),
            close = kline.getString("c").toDouble(),
            volume = kline.getString("v").toDouble(),
            closeTime = kline.getLong("T"),
            closed = kline.optBoolean("x"),
        )
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
        val REST_HOSTS: List<String> = listOf(
            "https://api.binance.com",
            "https://data-api.binance.vision",
        )
        val WS_ROOTS: List<String> = listOf(
            "wss://stream.binance.com:9443/ws/",
            "wss://data-stream.binance.vision/ws/",
        )
    }
}

data class KlineFetch(
    val candles: List<Candle>,
    val host: String,
    val notes: List<String>,
)
