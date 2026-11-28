package com.decisionoverlay.checker

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Production REST client; request signing happens only inside the Android app process. */
internal class KalshiPhoneApi(private val context: Context) {
    companion object {
        private const val BASE = "https://external-api.kalshi.com/trade-api/v2"
        private const val PREFIX = "/trade-api/v2"
    }
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()

    fun request(method: String, path: String, payload: JSONObject? = null,
                authenticated: Boolean = false): JSONObject {
        require(method == "GET" || method == "POST")
        require(path.startsWith("/") && !path.startsWith("//"))
        val pathToSign = PREFIX + path.substringBefore("?")
        val builder = Request.Builder().url(BASE + path)
            .header("Accept", "application/json")
        if (authenticated) {
            val (id, key) = KalshiPhoneCredentials.get(context)
            val stamp = System.currentTimeMillis().toString()
            val message = stamp + method + pathToSign
            builder.header("KALSHI-ACCESS-KEY", id)
                .header("KALSHI-ACCESS-TIMESTAMP", stamp)
                .header("KALSHI-ACCESS-SIGNATURE",
                    KalshiPhoneCredentials.sign(key, message.toByteArray(StandardCharsets.UTF_8)))
        }
        val req = if (method == "POST") {
            builder.post((payload ?: JSONObject()).toString()
                .toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        } else builder.get().build()
        return http.newCall(req).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("Kalshi HTTP ${response.code} for ${path.substringBefore("?")}")
            JSONObject(body)
        }
    }

    data class Funds(val equityCents: Long, val cashCents: Long) {
        val dollars: String get() = BigDecimal(equityCents).movePointLeft(2).toPlainString()
    }

    fun balance(): Funds {
        val r = request("GET", "/portfolio/balance", authenticated = true)
        // Balance values are cents in the API. portfolio_value is the value of open contracts.
        val cash = r.getLong("balance")
        val positions = r.optLong("portfolio_value", 0)
        require(cash >= 0 && positions >= 0 && cash < 10_000_000_000L
            && positions < 10_000_000_000L) { "Invalid account balance" }
        return Funds(Math.addExact(cash, positions), cash)
    }

    data class Market(val ticker: String, val closeTime: Instant, val status: String)

    fun currentMarket(now: Instant): Market? {
        val r = request("GET", "/markets?series_ticker=KXBTC15M&status=open&limit=100")
        val markets = r.optJSONArray("markets") ?: return null
        val matches = mutableListOf<Market>()
        for (i in 0 until markets.length()) {
            val m = markets.getJSONObject(i)
            val ticker = m.optString("ticker")
            if (!ticker.startsWith("KXBTC15M-")) continue
            val close = runCatching { Instant.parse(m.getString("close_time")) }.getOrNull() ?: continue
            val start = close.minusSeconds(900)
            if (!now.isBefore(start) && now.isBefore(close)) {
                matches.add(Market(ticker, close, m.optString("status")))
            }
        }
        return matches.singleOrNull()
    }

    fun market(ticker: String): JSONObject {
        require(Regex("^KXBTC15M-[A-Za-z0-9-]+$").matches(ticker))
        return request("GET", "/markets/$ticker").getJSONObject("market")
    }

    fun ask(ticker: String, outcome: String): BigDecimal? {
        require(outcome == "YES" || outcome == "NO")
        require(Regex("^KXBTC15M-[A-Za-z0-9-]+$").matches(ticker))
        val book = request("GET", "/markets/$ticker/orderbook").getJSONObject("orderbook_fp")
        val opposingBids = book.optJSONArray(if (outcome == "YES") "no_dollars" else "yes_dollars")
            ?: return null
        var best: BigDecimal? = null
        for (i in 0 until opposingBids.length()) {
            val price = opposingBids.getJSONArray(i).getString(0).toBigDecimal()
            if (price > BigDecimal.ZERO && price < BigDecimal.ONE
                && (best == null || price > best)) best = price
        }
        return best?.let { BigDecimal.ONE.subtract(it) }
    }

    data class Submitted(val clientId: String, val orderId: String?)

    fun submit(ticker: String, outcome: String, count: Int, clientId: String): Submitted {
        require(outcome == "YES" || outcome == "NO")
        require(ticker.startsWith("KXBTC15M-") && count in 1..1000)
        val body = JSONObject()
            .put("ticker", ticker)
            .put("client_order_id", clientId)
            .put("side", if (outcome == "YES") "bid" else "ask")
            .put("count", count.toString())
            .put("price", if (outcome == "YES") "0.6000" else "0.4000")
            .put("time_in_force", "immediate_or_cancel")
            .put("post_only", false)
            .put("self_trade_prevention_type", "taker_at_cross")
            .put("cancel_order_on_pause", true)
        val r = request("POST", "/portfolio/events/orders", body, true)
        val info = r.optJSONObject("order") ?: r
        return Submitted(clientId, info.optString("order_id").ifBlank { null })
    }

    fun fills(orderId: String): List<JSONObject> {
        val all = mutableListOf<JSONObject>()
        var cursor = ""
        repeat(20) {
            val r = request("GET", "/portfolio/fills?order_id=$orderId&limit=1000" +
                (if (cursor.isEmpty()) "" else "&cursor=$cursor"), authenticated = true)
            val rows = r.optJSONArray("fills") ?: JSONArray()
            for (i in 0 until rows.length()) all.add(rows.getJSONObject(i))
            cursor = r.optString("cursor")
            if (cursor.isBlank() || cursor == "null") return all
        }
        error("Too many fills to reconcile safely")
    }

    fun newClientId(): String = UUID.randomUUID().toString()
}
