package com.decisionoverlay.checker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BtcTouchTimingTest {
    private fun market(id: Long, up: Boolean, levels: List<Int>) =
        BinanceTwentySevenPatternReport.Market(id, 100.0, if (up) 101.0 else 99.0,
            levels.mapIndexed { i, level -> BinanceTwentySevenPatternReport.Observation(i+1,level.toDouble()) })

    @Test fun repeatedLevelMinutesCountTowardSpeedAndStopAtCrossing() {
        val csv = BtcSequenceOutcomes.report(listOf(
            market(1,false,listOf(55,55,70,70,70,60,80,-55,90)),
            market(2,true,listOf(55,55,70,60,80,80,90))))
        val rows = csv.lines().drop(1).filter { it.isNotBlank() }.map { it.split(',') }
        fun row(kind: String, path: String, gaps: String) = rows.single {
            it[0]=="70" && it[1]==kind && it[2]==path && it[5]==gaps
        }
        assertEquals("3>6>7",row("exact","70>60>80","3+1")[3])
        assertEquals("8",row("exact","70>60>80","3+1")[4])
        assertEquals("100.0",row("speed","70>60","3")[10])
        assertEquals("100.0",row("speed","70>60","1")[11])
        assertEquals("2",row("sequence","70>60","")[7])
        assertEquals("3>8",row("touch_to_target","target","5")[3])
        assertTrue(BtcSequenceOutcomes.detail(csv,"70","70>60>80").contains("M3 → M6 → M7"))
    }
}
