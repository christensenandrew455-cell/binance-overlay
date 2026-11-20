package com.decisionoverlay.checker

import org.json.JSONObject

/**
 * Binance.US BTC/USDT spot order book, NOT Kalshi contract bids/asks.
 * This is an experimental directional check only; its use has not been
 * backtested with historical depth snapshots.
 */
internal object BtcSpotOrderBook {
    data class Depth(
        val receivedAt: Long, val bestBid: Double, val bestAsk: Double,
        val bidNotional: Double, val askNotional: Double
    ) {
        val bidShare get() = bidNotional / (bidNotional + askNotional)
        val spreadBps get() = 10_000.0 * (bestAsk - bestBid) /
            ((bestAsk + bestBid) / 2.0)
        fun fresh(now: Long): Boolean =
            now - receivedAt in 0L..20_000L && bidShare.isFinite() &&
            spreadBps.isFinite() && spreadBps >= 0.0 && spreadBps <= 10.0

        fun supports(up: Boolean, now: Long): Boolean =
            fresh(now) && (if (up) bidShare >= 0.55 else bidShare <= 0.45)

        fun asJson() = JSONObject()
            .put("at", receivedAt).put("bid", bestBid).put("ask", bestAsk)
            .put("bid_usdt", bidNotional).put("ask_usdt", askNotional)
            .put("bid_share", bidShare).put("spread_bps", spreadBps)
    }

    fun parse(json: JSONObject, receivedAt: Long): Depth? {
        val bids = json.optJSONArray("bids") ?: return null
        val asks = json.optJSONArray("asks") ?: return null
        if (bids.length() < 5 || asks.length() < 5) return null
        fun amount(side: org.json.JSONArray): Pair<Double, Double>? {
            var best = Double.NaN
            var usd = 0.0
            val take = minOf(20, side.length())
            for (i in 0 until take) {
                val row = side.optJSONArray(i) ?: return null
                val price = row.optString(0).toDoubleOrNull() ?: return null
                val quantity = row.optString(1).toDoubleOrNull() ?: return null
                if (!price.isFinite() || !quantity.isFinite() ||
                    price <= 0 || quantity < 0) return null
                if (i == 0) best = price
                usd += price * quantity
            }
            return if (usd.isFinite() && usd > 0) best to usd else null
        }
        val bid = amount(bids) ?: return null
        val ask = amount(asks) ?: return null
        if (bid.first >= ask.first) return null
        return Depth(receivedAt, bid.first, ask.first, bid.second, ask.second)
    }
    fun fromSnapshot(json: JSONObject?): Depth? {
        if (json == null) return null
        return try {
            val value = Depth(json.getLong("at"), json.getDouble("bid"),
                json.getDouble("ask"), json.getDouble("bid_usdt"),
                json.getDouble("ask_usdt"))
            if (value.bestBid > 0.0 && value.bestBid < value.bestAsk &&
                value.bidNotional > 0.0 && value.askNotional > 0.0 &&
                value.bidShare.isFinite()) value else null
        } catch (_: Exception) { null }
    }
}
