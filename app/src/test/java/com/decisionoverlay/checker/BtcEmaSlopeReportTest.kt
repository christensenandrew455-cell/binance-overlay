package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test

class BtcEmaSlopeReportTest {
    private val m=60_000L
    private fun candles(n: Int, rising: Boolean=true): Map<Long,BtcAcmMath.Candle> =
        (0 until n).associate { i ->
            val p=10000.0 + if(rising) i.toDouble() else -i.toDouble()
            i*m to BtcAcmMath.Candle(i*m,p,p,p,p)
        }

    @Test fun gridHasExactly900UniqueConfigurations() {
        assertEquals(900,BtcEmaSlopeReport.configs.size)
        assertEquals(900,BtcEmaSlopeReport.configs.toSet().size)
    }

    @Test fun slopeUsesOnlyClosedNativeAlignedCandles() {
        val data=candles(600)
        val c=BtcEmaSlopeReport.Config(15,5,3)
        val start=450*m
        val before=BtcEmaSlopeReport.ema(data.filterKeys{it<start},15,5)
        val after=BtcEmaSlopeReport.ema(data,15,5)
        val slope=BtcEmaSlopeReport.slope(before,start,c)!!
        assertTrue(slope>0)
        assertEquals(slope,BtcEmaSlopeReport.slope(after,start,c)!!,0.0)
        assertNull(BtcEmaSlopeReport.slope(before,start+15*m,c))
    }

    @Test fun fallingHistoryHasNegativeSlopeAndMissingBlocksResetWarmup() {
        val data=candles(600,false)
        val c=BtcEmaSlopeReport.Config(5,5,1)
        val start=450*m
        val values=BtcEmaSlopeReport.ema(data,5,5)
        assertTrue(BtcEmaSlopeReport.slope(values,start,c)!!<0)
        val gap=BtcEmaSlopeReport.ema(data.filterKeys{it!=440*m},5,5)
        assertNull(BtcEmaSlopeReport.slope(gap,start,c))
    }

    @Test fun reportRetainsSchemaAndScoresFrozenDiscoveryWinner() {
        val data=candles(8000)
        val markets=(0 until 10).map { i ->
            val target=10000.0
            BinanceTwentySevenPatternReport.Market((7755L+15*i)*m,target,
                if(i<7) target+1 else target-1,emptyList())
        }
        val rows=BtcEmaSlopeReport.report(markets,data).trim().lines().map{it.split(",")}
        assertTrue(rows.all{it.size==31})
        val selected=rows.filter{it[1]=="ema_slope_selected" && it[2]=="BOTH"}
        assertEquals(2,selected.size)
        assertEquals(selected[0][3],selected[1][3])
        assertEquals("7",selected[0][9])
        assertEquals("0",selected[1][9])
        assertEquals(2700,rows.count{it[1]=="ema_slope_grid" && it[2]=="BOTH"})
    }
}
