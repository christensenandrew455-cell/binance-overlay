package com.decisionoverlay.checker

import org.json.JSONObject

/**
 * Serialize the SAME historical training directions as the five-vote test:
 * 700 oldest of the completed 1,000 BTC sections. This is a research signal,
 * never a probability or executable contract recommendation.
 */
internal object BtcLiveCalibration {
    const val KEY = "live_five_vote_model_v1"
    private val CHECKPOINTS = setOf(11, 8, 5)
    data class Vote(val up: Boolean, val chartUp: Boolean, val upVotes: Int) {
        val overridesChart get() = up != chartUp
    }
    private class Counts {
        var hits = 0
        var ups = 0
        fun add(up: Boolean) { hits++; if (up) ups++ }
        fun decision(minSamples: Int): Boolean? =
            if (hits < minSamples || ups * 2 == hits) null else ups * 2 > hits
    }
    private fun chartKey(band: Int, left: Int) = "$band/$left"
    private fun signalKey(name: String, state: Int, left: Int) = "$name/$state/$left"

    fun train(markets: List<BtcEdgeTest.Market>): String {
        require(markets.size == 1000 && markets.map { it.at }.distinct().size == 1000)
        val ordered = markets.sortedBy { it.at }
        val chart = mutableMapOf<String, Counts>()
        val indicators = mutableMapOf<String, Counts>()
        for (market in ordered.take(700)) for (row in market.rows) {
            if (row.left !in CHECKPOINTS) continue
            chart.getOrPut(chartKey(row.band, row.left)) { Counts() }.add(market.up)
            for (name in BtcFiveIndicatorEnsemble.NAMES) {
                val signal = row.signals.singleOrNull {
                    it.indicator == name && it.timeframe == 1 && it.state != 0
                } ?: continue
                indicators.getOrPut(signalKey(name, signal.state, row.left)) {
                    Counts()
                }.add(market.up)
            }
        }
        val c = JSONObject()
        chart.forEach { (key, value) -> value.decision(1)?.let { c.put(key, it) } }
        val i = JSONObject()
        indicators.forEach { (key, value) ->
            value.decision(40)?.let { i.put(key, it) }
        }
        return JSONObject().put("version", 1)
            .put("trained_markets", 700)
            .put("reference_market_start", ordered.last().at)
            .put("last_training_market_start", ordered[699].at)
            .put("chart", c).put("indicators", i).toString()
    }

    /**
     * Only an actual unanimous 5/5 learned vote is a live signal.
     * We do not treat positive technical readings as necessarily UP:
     * each state/direction relationship was fitted on past markets.
     */
    fun vote(model: String?, band: Int, left: Int, states: Map<String, Int>): Vote? {
        if (model.isNullOrBlank() || left !in CHECKPOINTS) return null
        val parsed = try { JSONObject(model) } catch (_: Exception) { return null }
        if (parsed.optInt("version") != 1 ||
            parsed.optInt("trained_markets") != 700) return null
        val charts = parsed.optJSONObject("chart") ?: return null
        val all = parsed.optJSONObject("indicators") ?: return null
        val chartId = chartKey(band, left)
        if (!charts.has(chartId)) return null
        val predictions = BtcFiveIndicatorEnsemble.NAMES.map { name ->
            val state = states[name] ?: return null
            if (state == 0) return null
            val id = signalKey(name, state, left)
            if (!all.has(id)) return null
            all.getBoolean(id)
        }
        val upVotes = predictions.count { it }
        if (upVotes != 0 && upVotes != 5) return null
        return Vote(upVotes == 5, charts.getBoolean(chartId), upVotes)
    }
}
