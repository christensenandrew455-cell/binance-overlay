package com.decisionoverlay.checker

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Reads public Binance.US market data. No account, API key, or trading permission is needed.
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
                val request = Request.Builder().url(url)
                    .header("Accept", "application/json")
                    .header("User-Agent", "DecisionOverlay/1.1")
                    .build()
                http.newCall(request).also { it.timeout().timeout(20, TimeUnit.SECONDS) }
                    .execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            notes += "$host HTTP " + response.code + ": " + body.take(180)
                        } else if (!body.trimStart().startsWith("[")) {
                            notes += "$host returned an unexpected response: " + body.take(180)
                        } else {
                            val parsed = parseKlines(body)
                            if (parsed.isEmpty()) notes += "$host returned zero candles"
                            else {
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

    /**
     * Binance.US partial-depth stream sends a fresh top-10 book snapshot every 100 ms.
     * Unlike diff-depth, each message is already a complete partial snapshot, so no
     * REST snapshot/update-ID synchronization is needed for these displayed top levels.
     */
    fun openOrderBookStream(symbol: String, onSnapshot: (OrderBookSnapshot) -> Unit,
                            onDisconnected: () -> Unit): WebSocket {
        val stream = symbol.lowercase(Locale.US) + "@depth10@100ms"
        val request = Request.Builder()
            .url("wss://stream.binance.us:9443/ws/$stream")
            .header("User-Agent", "DecisionOverlay/1.1")
            .build()
        return http.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    if (!json.has("bids") || !json.has("asks")) return
                    onSnapshot(parseDepth(json.getJSONArray("bids"), json.getJSONArray("asks")))
                } catch (_: Exception) {
                    // Ignore malformed/partial frames; the next complete depth frame replaces them.
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                onDisconnected()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                onDisconnected()
            }
        })
    }

    fun fetchOrderBook(symbol: String, limit: Int = 100): OrderBookSnapshot {
        val url = "${activeRestHost}/api/v3/depth?symbol=$symbol&limit=$limit"
        val request = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "DecisionOverlay/1.1")
            .build()
        http.newCall(request).also { it.timeout().timeout(8, TimeUnit.SECONDS) }
            .execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw IllegalStateException("Order book HTTP ${response.code}")
                val json = JSONObject(body)
                return parseDepth(json.getJSONArray("bids"), json.getJSONArray("asks"))
            }
    }

    private fun parseDepth(bids: JSONArray, asks: JSONArray): OrderBookSnapshot {
        if (bids.length() == 0 || asks.length() == 0) throw IllegalStateException("Empty order book")
        fun side(levels: JSONArray): Triple<Double, Double, Double> {
            var quantity = 0.0
            var notional = 0.0
            var weightedNotional = 0.0
            for (i in 0 until minOf(10, levels.length())) {
                val level = levels.getJSONArray(i)
                val price = level.getString(0).toDouble()
                val size = level.getString(1).toDouble()
                quantity += size
                val value = price * size
                notional += value
                // Closer levels receive more weight; distant levels still contribute.
                val proximityWeight = 1.0 / (1.0 + i * 0.25)
                weightedNotional += value * proximityWeight
            }
            return Triple(quantity, notional, weightedNotional)
        }
        val bid = side(bids)
        val ask = side(asks)
        val weightedTotal = bid.third + ask.third
        val bestBid = bids.getJSONArray(0).getString(0).toDouble()
        val bestAsk = asks.getJSONArray(0).getString(0).toDouble()
        return OrderBookSnapshot(
            bidVolume = bid.first,
            askVolume = ask.first,
            bestBid = bestBid,
            bestAsk = bestAsk,
            imbalance = if (weightedTotal > 0.0) (bid.third - ask.third) / weightedTotal else 0.0,
            spread = bestAsk - bestBid,
            capturedAtMs = System.currentTimeMillis(),
            bidNotional = bid.second,
            askNotional = ask.second,
            nearBidNotional = bid.third,
            nearAskNotional = ask.third,
        )
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
            val takerBuyVolume = if (kline.length() > 9) kline.getString(9).toDouble() else 0.0
            if (!open.isFinite() || !high.isFinite() || !low.isFinite() ||
                !close.isFinite() || !volume.isFinite() || !takerBuyVolume.isFinite()
            ) continue
            val closeTime = kline.getLong(6)
            candles += Candle(
                openTime = kline.getLong(0), open = open, high = high, low = low,
                close = close, volume = volume, closeTime = closeTime,
                closed = closeTime < now, takerBuyVolume = takerBuyVolume,
            )
        }
        return candles
    }

    companion object {
        val REST_HOSTS = listOf(
            "https://api.binance.us",
            "https://api1.binance.us",
            "https://api2.binance.us",
        )
    }
}

data class OrderBookSnapshot(
    val bidVolume: Double,
    val askVolume: Double,
    val bestBid: Double,
    val bestAsk: Double,
    val imbalance: Double,
    val spread: Double,
    val capturedAtMs: Long = System.currentTimeMillis(),
    val bidNotional: Double = 0.0,
    val askNotional: Double = 0.0,
    val nearBidNotional: Double = 0.0,
    val nearAskNotional: Double = 0.0,
)

data class KlineFetch(
    val candles: List<Candle>,
    val host: String,
    val notes: List<String>,
)
