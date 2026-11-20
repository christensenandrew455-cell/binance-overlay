package com.decisionoverlay.checker

import org.junit.Assert.assertTrue
import org.junit.Test

class BtcEdgeTestTest {
    @Test fun countsAllMarketsOncePerSetupIncludingCounterBandFinishes() {
        val rows = listOf(6.0, 5.0).map {
            BtcEdgeTest.Row(60, doubleArrayOf(1.0, -1.0, 0.0, 0.0, it))
        }
        val report = BtcEdgeTest.report(List(1000) { BtcEdgeTest.Market(it < 350, rows) })
        assertTrue(report.contains("Band 60 UP · 5 min left · 5m momentum (2 bars) down"))
        assertTrue(report.contains("Finish DOWN · hits 1000/1000 (100.0%) · wins 650/1000 (65.0%)"))
        assertTrue(report.contains("1m close vs target up + 5m momentum (2 bars) down"))
    }
}
