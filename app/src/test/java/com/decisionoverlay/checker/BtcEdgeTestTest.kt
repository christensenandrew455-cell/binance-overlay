package com.decisionoverlay.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class BtcEdgeTestTest {
    @Test fun holdoutLabelsCannotChangeSignals() {
        fun sample(up: Boolean) = BtcEdgeTest.Market(up, listOf(
            BtcEdgeTest.Row(if (up) 60 else -60, doubleArrayOf(if (up) 1.0 else -1.0,0.0,0.0,0.0,3.0))))
        val training = List(700) { sample(it%2==0) }
        val test = List(300) { sample(it%2==0) }
        val correct = BtcEdgeTest.report(training+test)
        val flipped = BtcEdgeTest.report(training+test.map { it.copy(up = !it.up) })
        assertTrue(correct.contains("300/300 hits (100.0%) · 300/300 wins (100.0%)"))
        assertTrue(correct.contains("actual minus band +40.0 points"))
        assertTrue(flipped.contains("300/300 hits (100.0%) · 0/300 wins (0.0%)"))
    }
}
