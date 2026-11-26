package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BtcReversalTimingReportTest {
    private fun market(quarter: Int, levels: List<Double>, final: Double): BtcReversalTimingReport.Market {
        val start=LocalDate.of(2026,11,1).atTime(10,0)
            .atZone(ZoneId.of("America/New_York")).plusMinutes(quarter*15L).toInstant().toEpochMilli()
        val observations=levels.mapIndexed { i,x ->
            BinanceTwentySevenPatternReport.Observation(i+1,x,if(x<0) 99.0 else 101.0,102.0)
        }
        val base=BinanceTwentySevenPatternReport.Market(start,100.0,final,observations)
        val rows=levels.mapIndexed { i,x -> BtcReversalTimingReport.Row(i+1,x,-0.1,-0.2,-0.1,-0.3,2.0) }
        return BtcReversalTimingReport.Market(base,rows)
    }

    @Test fun directionFixedAndRepeatedHitsDeduplicated() {
        val a=market(0,listOf(85.0,65.0,85.0,65.0,-65.0),99.0)
        val b=market(1,listOf(85.0,65.0),101.0)
        val c=market(2,listOf(55.0,55.0),101.0)
        val csv=BtcReversalTimingReport.report(listOf(a,b,c,a))
        val row=csv.lineSequence().first { it.startsWith("all_descriptive,same_side_pullback,ABOVE_buy_DOWN,80_to_60,ALL,ALL,") }.split(',')
        assertEquals(listOf("3","2","66.67","1","1","50.00"),row.subList(6,12))
        assertEquals("1",row[14])
        val hold=csv.lineSequence().first { it.startsWith("newer_check,same_side_pullback,ABOVE_buy_DOWN,80_to_60,ALL,ALL,") }.split(',')
        assertEquals("1",hold[6]); assertEquals("0",hold[7]); assertEquals("",hold[11])
    }

    @Test fun oppositeSideResetsSequenceAndDirectionMirrors() {
        val a=market(0,listOf(85.0,-75.0,65.0),99.0)
        val b=market(1,listOf(-85.0,-65.0,65.0),101.0)
        val csv=BtcReversalTimingReport.report(listOf(a,b))
        assertFalse(csv.lineSequence().any { it.startsWith("all_descriptive,same_side_pullback,ABOVE_buy_DOWN,80_to_60,ALL,ALL,2,1,") })
        assertTrue(csv.contains("all_descriptive,same_side_pullback,BELOW_buy_UP,80_to_60,ALL,ALL,2,1,50.00,1,0,100.00"))
    }

    @Test fun dailyFirstAlertsPreserveDatesAndZeroMinutes() {
        val a=market(0,listOf(-55.0,-55.0,-65.0),99.0)
        val b=market(1,listOf(-55.0,-55.0,-65.0),101.0)
        val c=market(96,listOf(85.0,85.0,85.0),101.0)
        val csv=BtcReversalTimingReport.report(listOf(a,b,c,a))
        fun row(day: String, minute: String) = csv.lineSequence().first {
            it.startsWith("day_"+day+",existing_first_alert,BOTH,frozen_buy,"+minute+",ALL,")
        }.split(',')
        assertEquals(listOf("2","2","100.00","1","1","50.00"),row("2026-11-01","12").subList(6,12))
        assertEquals(listOf("2","2","100.00","1","1","50.00"),row("2026-11-01","ALL").subList(6,12))
        assertEquals(listOf("1","0","0.00","0","0",""),row("2026-11-02","12").subList(6,12))
        assertEquals(listOf("2","0","0.00","0","0",""),row("2026-11-01","14").subList(6,12))
    }
}
