package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class BtcSecondWarningReportTest {
    @Test fun cutoffNeverReadsFutureAndRejectsStalePrices() {
        val t=listOf(BtcSecondWarningReport.Trade(1,1000,100.0,1.0),
            BtcSecondWarningReport.Trade(2,15000,101.0,1.0),
            BtcSecondWarningReport.Trade(3,15001,102.0,1.0))
        assertEquals(100.0,BtcSecondWarningReport.priceAt(t,15000)!!,0.0)
        assertEquals(101.0,BtcSecondWarningReport.priceAt(t,15001)!!,0.0)
        assertNull(BtcSecondWarningReport.priceAt(t,31000))
    }

    @Test fun exactWarningsUsePriorCompletedBandAndMissingDataIsSeparate() {
        val start=LocalDate.of(2026,11,1).atTime(10,0)
            .atZone(ZoneId.of("America/New_York")).toInstant().toEpochMilli()
        val levels=listOf(-55.0,-55.0,-65.0)+List(11){-65.0}
        val base=BinanceTwentySevenPatternReport.Market(start,100.0,99.0,
            levels.mapIndexed { i,v -> BinanceTwentySevenPatternReport.Observation(i+1,v,96.0,100.0) })
        val rows=levels.mapIndexed { i,v -> BtcReversalTimingReport.Row(i+1,v,0.0,0.0,0.0,0.0,1.0,
            1.0,96.0,if(i==2)3.0 else 1.0) }
        val market=BtcReversalTimingReport.Market(base,rows)
        var id=0L
        val trades=mutableListOf<BtcSecondWarningReport.Trade>()
        for(i in 0..13) {
            val open=start+i*60_000L
            if(i==2) {
                trades.add(BtcSecondWarningReport.Trade(++id,open+25_000,97.5,1.0))
                trades.add(BtcSecondWarningReport.Trade(++id,open+40_000,97.5,1.0))
            }
            trades.add(BtcSecondWarningReport.Trade(++id,open+59_000,96.0,1.0))
        }
        val csv=BtcSecondWarningReport.report(listOf(market),{_,_->trades},{_,_->})
        val all=csv.lineSequence().filter { it.isNotBlank() }.map { it.split(',') }.toList()
        assertTrue(all.all { it.size==31 })
        val warnings=all.filter { it[0]=="all_descriptive" && it[1]=="early_warning_seconds" &&
            it[3]=="threshold_55.00" && it[4]=="ALL" && it[5]=="ALL" }
        assertEquals(2,warnings.size)
        assertTrue(warnings.all { it[7]=="1" && it[25]=="1" && it[26]=="0" })
        // A direct entry counts even if the close never confirms the signal.
        val secondStart=start+15*60_000L
        val secondBase=BinanceTwentySevenPatternReport.Market(secondStart,100.0,101.0,
            levels.mapIndexed { i,_ -> BinanceTwentySevenPatternReport.Observation(i+1,-55.0,96.0,100.0) })
        val second=BtcReversalTimingReport.Market(secondBase,rows.map { it.copy(level=-55.0) })
        val replay=BtcSecondWarningReport.report(listOf(market,second),{from,_->
            if(from==start)trades else trades.map { it.copy(at=it.at+15*60_000L) }
        },{_,_->})
        val direct=replay.lineSequence().first {
            it.startsWith("all_rounded80_in_sample,near_buy_direct,BOTH,first_15_or_30s_threshold_55.00,ALL,ALL,")
        }.split(',')
        assertEquals(listOf("2","2","100.00","1","1","50.00"),direct.subList(6,12))
        assertEquals("1",direct[25])
        assertEquals("lead15=0;lead30=2",direct[22])
        val hold=replay.lineSequence().first {
            it.startsWith("newer_older80,near_buy_direct,BOTH,first_15_or_30s_threshold_55.00,ALL,ALL,")
        }.split(',')
        assertEquals(listOf("1","1","100.00","0","1","0.00"),hold.subList(6,12))
        val missing=BtcSecondWarningReport.report(listOf(market),{_,_->null},{_,_->})
        val row=missing.lineSequence().first { it.startsWith("all_descriptive,second_data_coverage,BOTH,trade_candle_reconciliation,ALL,ALL,") }.split(',')
        assertEquals("0",row[7])
        assertEquals("1",row[10])
    }
}
