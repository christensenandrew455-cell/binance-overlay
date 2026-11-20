package com.decisionoverlay.checker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcLiveSignalTest {
    private val names = BtcFiveIndicatorEnsemble.NAMES
    private fun row(state: Int, left: Int = 8) = BtcEdgeTest.Row(60, left,
        names.map { BtcEdgeTest.Signal(1, it, state) })
    private fun market(id: Int, up: Boolean, state: Int): BtcEdgeTest.Market =
        BtcEdgeTest.Market(id.toLong(), up, listOf(row(state)))

    @Test fun liveModelMatchesTrainingOnlyAndUnanimityCanOverrideChart() {
        val training = (0 until 700).map { id ->
            market(id, id < 450, if (id < 450) 1 else -1)
        }
        // Every later holdout market is opposite, but the model must NOT
        // learn its outcome: both live vote directions are from training.
        val holdout = (700 until 1000).map { id -> market(id, false, 1) }
        val model = BtcLiveCalibration.train(training + holdout)
        val pos = names.associateWith { 1 }
        val neg = names.associateWith { -1 }
        val up = BtcLiveCalibration.vote(model, 60, 8, pos)
        val down = BtcLiveCalibration.vote(model, 60, 8, neg)
        assertNotNull(up)
        assertEquals(true, up!!.up)
        assertFalse(up.overridesChart)
        assertNotNull(down)
        assertEquals(false, down!!.up)
        assertTrue(down.overridesChart)
        assertNull(BtcLiveCalibration.vote(model, 60, 2, pos))
    }

    @Test fun modelRejectsMixedVotesIncompleteAndUntrainedData() {
        val set = (0 until 1000).map { id -> market(id, id % 2 == 0, if (id % 2 == 0) 1 else -1) }
        val model = BtcLiveCalibration.train(set)
        val pos = names.associateWith { 1 }
        val mixed = names.mapIndexed { i, name -> name to if (i == 0) -1 else 1 }.toMap()
        assertNull(BtcLiveCalibration.vote(model, 60, 8, mixed))
        assertNull(BtcLiveCalibration.vote(model, 60, 8, pos - names.first()))
        assertNull(BtcLiveCalibration.vote(null, 60, 8, pos))
        assertNull(BtcLiveCalibration.vote("{}", 60, 8, pos))
    }

    @Test fun orderBookParsesBidAskDepthAndEnforcesFreshDirectionalCheck() {
        val bids = JSONArray()
        val asks = JSONArray()
        repeat(20) { idx ->
            bids.put(JSONArray().put((100.0 - idx / 100.0).toString()).put("8"))
            asks.put(JSONArray().put((100.02 + idx / 100.0).toString()).put("3"))
        }
        val book = BtcSpotOrderBook.parse(
            JSONObject().put("bids", bids).put("asks", asks), 1_000_000L)
        assertNotNull(book)
        assertTrue(book!!.bidShare > 0.55)
        assertTrue(book.spreadBps < 10.0)
        assertTrue(book.supports(true, 1_015_000L))
        assertFalse(book.supports(false, 1_015_000L))
        assertFalse(book.supports(true, 1_020_001L))
        assertEquals(book, BtcSpotOrderBook.fromSnapshot(book.asJson()))
    }

    @Test fun rejectsCrossedAndEmptySpotBooks() {
        val crossed = JSONObject().put("bids", JSONArray().apply {
            repeat(5) { put(JSONArray().put("101").put("1")) }
        }).put("asks", JSONArray().apply {
            repeat(5) { put(JSONArray().put("100").put("1")) }
        })
        assertNull(BtcSpotOrderBook.parse(crossed, 1000L))
        assertNull(BtcSpotOrderBook.parse(JSONObject(), 1000L))
        assertNull(BtcSpotOrderBook.fromSnapshot(JSONObject()))
    }
}
