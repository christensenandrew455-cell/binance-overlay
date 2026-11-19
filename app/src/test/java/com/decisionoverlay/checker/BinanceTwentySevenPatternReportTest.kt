package com.decisionoverlay.checker

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class BinanceTwentySevenPatternReportTest {
    private val zone = ZoneId.of("America/New_York")
    private fun millis(hour: Int, minute: Int) =
        ZonedDateTime.of(2026, 11, 19, hour, minute, 0, 0, zone)
            .toInstant().toEpochMilli()

    @Test fun fullLockedTwentySevenPatternsArePreserved() {
        val rules = BinanceTwentySevenPatternReport.rules
        assertEquals(27, rules.size)
        assertEquals(27, rules.distinct().size)
        assertEquals(listOf(40, 50), rules.first().steps)
        assertEquals("DOWN", rules.first().direction)
        assertEquals(listOf(50, 70, 60), rules.last().steps)
        assertTrue(rules.any { it.steps == listOf(70, 60) && !it.buyUp })
        assertTrue(rules.any { it.steps == listOf(30, 40) && it.buyUp })
    }

    @Test fun chartLevelsMapOnBothSidesRatherThanKalshiQuotePercentage() {
        val band = BinanceTwentySevenPatternReport::band
        assertEquals(50, band(0.0))
        assertEquals(50, band(55.0))
        assertEquals(50, band(-55.0))
        assertEquals(40, band(-65.0))
        assertEquals(30, band(-75.0))
        assertEquals(20, band(-85.0))
        assertEquals(10, band(-90.0))
        assertEquals(60, band(65.0))
        assertEquals(70, band(75.0))
        assertEquals(80, band(85.0))
        assertEquals(90, band(90.0))
        assertEquals(-1, band(Double.NaN))
    }

    private fun market(id: Long, up: Boolean,
        values: List<Pair<Int, Double>>) = BinanceTwentySevenPatternReport.Market(
        millis(10, 0) + id * 900_000L, 100.0,
        if (up) 101.0 else 99.0,
        values.map { BinanceTwentySevenPatternReport.Observation(it.first, it.second) })

    @Test fun orderedStagesNeverUseSettlementOrFirstThreeMinutes() {
        val rule = BinanceTwentySevenPatternReport.rules.first {
            it.steps == listOf(70, 60) && !it.buyUp
        }
        assertNull(BinanceTwentySevenPatternReport.firstHit(
            market(1, false, listOf(1 to 75.0, 2 to 65.0)), rule))
        assertEquals(11, BinanceTwentySevenPatternReport.firstHit(
            market(2, false, listOf(1 to 75.0, 4 to 65.0)), rule)?.left)
        assertNull(BinanceTwentySevenPatternReport.firstHit(
            market(3, false, listOf(4 to 65.0, 5 to 75.0)), rule))
        assertNull(BinanceTwentySevenPatternReport.firstHit(
            market(4, false, listOf(13 to 75.0, 15 to 65.0)), rule))
        assertTrue(BinanceTwentySevenPatternReport.firstHit(
            market(5, false, listOf(1 to 75.0, 4 to 65.0)), rule)!!.won)
        assertFalse(BinanceTwentySevenPatternReport.firstHit(
            market(6, true, listOf(1 to 75.0, 4 to 65.0)), rule)!!.won)
    }

    @Test fun reversedAndThreeStageReboundPatternsCount() {
        val upRule = BinanceTwentySevenPatternReport.rules.first {
            it.steps == listOf(30, 40) && it.buyUp
        }
        val bounceRule = BinanceTwentySevenPatternReport.rules.first {
            it.steps == listOf(50, 30, 40) && it.buyUp
        }
        assertTrue(BinanceTwentySevenPatternReport.firstHit(
            market(1, true, listOf(1 to -75.0, 5 to -65.0)), upRule)!!.won)
        assertEquals(7, BinanceTwentySevenPatternReport.firstHit(
            market(2, true, listOf(1 to -55.0, 4 to -75.0, 8 to -65.0)),
            bounceRule)?.left)
    }

    @Test fun easternSessionIsTenAmToElevenPmPrecisely() {
        val allowed = BinanceTwentySevenPatternReport::isAllowedSession
        assertFalse(allowed(millis(9, 45)))
        assertTrue(allowed(millis(10, 0)))
        assertTrue(allowed(millis(22, 45)))
        assertFalse(allowed(millis(23, 0)))
    }

    @Test fun reportShowsAll27WithHitRateWinRateAndTimeframe() {
        val markets = (0 until 1000).map { i ->
            val sequence = if (i < 120)
                listOf(1 to 75.0, 7 to 65.0)
            else listOf(1 to -90.0, 7 to -90.0)
            market(i.toLong(), i >= 90 && i < 120, sequence)
        }
        val report = BinanceTwentySevenPatternReport.report(markets)
        assertTrue(report.contains("1000/1000"))
        assertTrue(report.contains("SOURCE: Binance.US"))
        assertTrue(report.contains("70% -> 60%,DOWN,120,12.0,90,75.0,8-5m,120"))
        assertTrue(report.contains("=== EXACT COUNTS BY TIME LEFT ==="))
        assertTrue(report.contains("40% -> 50%"))
        assertTrue(report.contains("50% -> 70% -> 60%"))
        assertFalse(report.contains("BUY-side ask"))
        assertFalse(report.contains("INCOMPLETE:"))
    }

    @Test fun sevenImagePathShapesUseTargetCenteredLevelsAndAreReported() {
        val paths = BinanceSevenPathReport.paths
        assertEquals(7, paths.size)
        assertEquals(listOf(40, 60), paths[0].rule.steps)
        assertEquals(listOf(50, 60), paths[1].rule.steps)
        assertEquals(listOf(40, 20), paths[2].rule.steps)
        assertEquals(listOf(50, 20), paths[3].rule.steps)
        assertEquals(listOf(30, 20), paths[4].rule.steps)
        assertEquals(listOf(60, 20), paths[5].rule.steps)
        assertEquals(listOf(70, 60), paths[6].rule.steps)

        val bands = listOf(
            listOf(-65.0, 65.0), listOf(0.0, 65.0),
            listOf(-65.0, -85.0), listOf(0.0, -85.0),
            listOf(-75.0, -85.0), listOf(65.0, -85.0),
            listOf(75.0, 65.0)
        )
        val markets = paths.mapIndexed { i, path ->
            val buyUp = path.rule.buyUp
            market(10_000L + i, buyUp, listOf(
                4 to bands[i][0], 5 to bands[i][1]
            ))
        }
        val results = BinanceSevenPathReport.results(markets)
        assertEquals(7, results.size)
        assertTrue(results.all { it.hits == 1 && it.wins == 1 })
        assertTrue(BinanceSevenPathReport.report(markets)
            .contains("01 40% -> 60%,DOWN,1,14.3,1,100.0"))
    }
}
