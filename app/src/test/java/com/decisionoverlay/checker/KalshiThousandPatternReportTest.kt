package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.time.ZoneId

class KalshiThousandPatternReportTest {
    private val eastern = ZoneId.of("America/New_York")
    private fun market(id: Int, bands: Map<Int, Int>, won: Boolean = true) =
        KalshiHundredMarketReport.Market(
            "KXBTC15M-VALIDATE-" + id,
            ZonedDateTime.of(2026,11,15,10,15,0,0,eastern).toEpochSecond(),
            won, bands.map { (minute, band) ->
                KalshiHundredMarketReport.MinutePrice(minute,band.toDouble()+1.0,false)
            }
        )

    @Test fun allTwentySevenOriginalRulesAreLocked() {
        val rules = KalshiThousandPatternReport.rules
        assertEquals(27,rules.size)
        assertEquals(27,rules.distinct().size)
        assertEquals(listOf(40,50),rules.first().steps)
        assertEquals("DOWN",rules.first().direction)
        assertEquals(listOf(50,70,60),rules.last().steps)
    }
    @Test fun exactBandsOrderedDistinctMinutesAndFirstHit() {
        val r=KalshiThousandPatternReport.rules.first()
        val m=market(1,mapOf(1 to 40,2 to 30,4 to 50,5 to 40,6 to 50),false)
        assertEquals(11,KalshiThousandPatternReport.firstHit(m,r)?.left)
        assertTrue(KalshiThousandPatternReport.firstHit(m,r)!!.won)
        assertNull(KalshiThousandPatternReport.firstHit(
            market(2,mapOf(4 to 40,5 to 60)),r))
        // The helper adds 1, so 48 becomes 49 in the 40-band, not 50.
        assertNull(KalshiThousandPatternReport.firstHit(
            market(3,mapOf(4 to 40,5 to 48)),r))
    }
    @Test fun noTradingDuringFirstThreeOrAtSettlement() {
        val r=KalshiThousandPatternReport.rules.first()
        assertNull(KalshiThousandPatternReport.firstHit(
            market(1,mapOf(1 to 40,2 to 50,4 to 40,5 to 50)),r))
        assertNull(KalshiThousandPatternReport.firstHit(
            market(2,mapOf(13 to 40,15 to 50)),r))
        assertEquals(1,KalshiThousandPatternReport.firstHit(
            market(3,mapOf(13 to 40,14 to 50)),r)?.left)
    }
    @Test fun reportCountsDistinctMarketsAndReportsAll27IncludingRare() {
        val valid = (1..1000).map { i ->
            market(i, if(i<=150) mapOf(1 to 40,4 to 50)
                else mapOf(1 to 90,4 to 90), won = i % 3 != 0)
        }
        val report=KalshiThousandPatternReport.report(valid)
        assertTrue(report.contains("1000/1000"))
        assertFalse(report.contains("INCOMPLETE:"))
        assertTrue(report.contains("40% -> 50%,DOWN,150,15.0,50,33.3"))
        assertTrue(report.contains("=== FREQUENT: STRICTLY ABOVE 10% HIT RATE ==="))
        assertTrue(report.contains("=== ALL PATTERNS BY FIXED TIMEFRAME ==="))
        assertTrue(report.contains("n/a"))
    }
}
