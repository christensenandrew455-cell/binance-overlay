package com.decisionoverlay.checker

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Target-aware trend-leg projection using price swings, volume participation and MACD histogram. */
data class EarlySignalResult(
    val label: String,
    val targetPosition: String,
    val detail: String,
)

object EarlyReversalDetector {
    private data class Leg(
        val direction: Int,
        val move: Double,
        val minutes: Double,
        val averageVolume: Double,
    )

    fun evaluate(
        candles: List<Candle>,
        momentum: List<Double>,
        priceEma: List<Double>,
        targetPrice: Double?,
        emaPeriod: Int = 3,
        nowMs: Long = System.currentTimeMillis(),
    ): EarlySignalResult {
        val elapsedInMarket = nowMs % FIFTEEN_MINUTES_MS
        val remainingMs = FIFTEEN_MINUTES_MS - elapsedInMarket
        if (remainingMs > FINAL_FIVE_MINUTES_MS || remainingMs <= 0L) {
            return EarlySignalResult("WAIT • FINAL 5 MINUTES ONLY", "WAITING",
                "Directional signals unlock with 5 minutes remaining.")
        }
        if (candles.size < 6 || momentum.size != candles.size || priceEma.size != candles.size) {
            return EarlySignalResult("WAIT • COLLECTING DATA", "TARGET UNKNOWN",
                "Waiting for at least 6 one-minute candles.")
        }

        val lastIndex = candles.lastIndex
        val price = candles[lastIndex].close
        if (targetPrice == null || !targetPrice.isFinite() || !price.isFinite()) {
            return EarlySignalResult("WAIT • TARGET UNAVAILABLE", "TARGET UNKNOWN",
                "Waiting for a valid live price and 15-minute target.")
        }

        val distance = targetPrice - price
        val targetPosition = when {
            distance < 0.0 -> "ABOVE TARGET"
            distance > 0.0 -> "BELOW TARGET"
            else -> "AT TARGET"
        }
        val legs = buildLegs(candles)
        val currentLeg = legs.lastOrNull()
        if (currentLeg == null || currentLeg.move <= 0.0) {
            return EarlySignalResult("WAIT • NO CLEAR PRICE LEG", targetPosition,
                "Waiting for a measurable directional price move.")
        }

        val direction = currentLeg.direction
        val sameDirection = legs.filter { it.direction == direction }.takeLast(3)
        val historicalLegSize = if (sameDirection.isEmpty()) currentLeg.move
            else sameDirection.map { it.move }.average()
        val historicalLegSpeed = sameDirection.map { it.move / it.minutes.coerceAtLeast(1.0) }.average()
        val priorLegs = legs.dropLast(1).filter { it.direction != direction }.takeLast(3)
        val pullbackAverage = priorLegs.map { it.move }.averageOrZero()
        val pullbackRatio = if (historicalLegSize > 0.0 && priorLegs.isNotEmpty())
            pullbackAverage / historicalLegSize else 0.0

        // Compare current directional-leg volume with recent candles. Clamp the adjustment
        // so one unusual volume bar cannot dominate the forecast.
        val recentVolume = candles.takeLast(3).map { it.volume }.average()
        val baselineVolume = candles.dropLast(3).takeLast(12).map { it.volume }.average()
            .takeIf { it > 0.0 } ?: recentVolume
        val volumeRatio = if (baselineVolume > 0.0) recentVolume / baselineVolume else 1.0
        val volumeFactor = volumeRatio.coerceIn(0.75, 1.25)

        // Histogram strength is relative to its own recent range, not a fixed dollar threshold.
        val recentMomentum = momentum.takeLast(6)
        val momentumScale = recentMomentum.map { abs(it) }.average().takeIf { it > 0.0 } ?: 1.0
        val momentumStrength = (abs(momentum.last()) / momentumScale).coerceIn(0.75, 1.20)
        val momentumAgrees = (direction > 0 && momentum.last() >= 0.0) ||
            (direction < 0 && momentum.last() <= 0.0)
        val momentumFactor = if (momentumAgrees) momentumStrength else 0.80

        // A pullback consumes part of the preceding impulse; volume and momentum adjust
        // the expected next leg without allowing either indicator to invent direction.
        val continuationFactor = (1.0 - min(0.35, pullbackRatio * 0.25)).coerceIn(0.65, 1.0)
        val projectedMove = historicalLegSize * continuationFactor * volumeFactor * momentumFactor
        val projectedSpeed = max(currentLeg.move / currentLeg.minutes.coerceAtLeast(1.0),
            historicalLegSpeed) * volumeFactor * momentumFactor
        val distanceToTarget = abs(distance)
        val minutesToTarget = if (projectedSpeed > 0.0) distanceToTarget / projectedSpeed
            else Double.POSITIVE_INFINITY
        val minutesRemaining = remainingMs / 60_000.0
        val pointsTowardTarget = (distance > 0.0 && direction > 0) ||
            (distance < 0.0 && direction < 0)
        val likelyToReach = pointsTowardTarget &&
            distanceToTarget <= projectedMove &&
            minutesToTarget <= minutesRemaining

        val emaNow = priceEma[lastIndex]
        val emaPrevious = priceEma[lastIndex - 1]
        val emaTrend = if (emaNow > emaPrevious) "rising" else if (emaNow < emaPrevious) "falling" else "flat"
        val label = when {
            likelyToReach && direction > 0 -> "EARLY UP TREND"
            likelyToReach && direction < 0 -> "EARLY DOWN TREND"
            pointsTowardTarget && direction > 0 -> "WAIT • UP TREND, MOVE MAY BE TOO SMALL"
            pointsTowardTarget && direction < 0 -> "WAIT • DOWN TREND, MOVE MAY BE TOO SMALL"
            distance > 0.0 -> "WAIT • BELOW TARGET"
            distance < 0.0 -> "WAIT • ABOVE TARGET"
            else -> "WAIT • AT TARGET"
        }
        val directionText = if (direction > 0) "UP" else "DOWN"
        val evidence = buildList {
            add("Actual price: " + String.format(java.util.Locale.US, "$%,.2f", price))
            add("Target: " + String.format(java.util.Locale.US, "$%,.2f", targetPrice))
            add("Current leg: $directionText " + String.format(java.util.Locale.US, "$%.2f", currentLeg.move))
            add("Typical $directionText leg: " + String.format(java.util.Locale.US, "$%.2f", historicalLegSize))
            add("Pullback: " + String.format(java.util.Locale.US, "$%.2f", pullbackAverage) +
                " (" + String.format(java.util.Locale.US, "%.0f%%", pullbackRatio * 100.0) + " of leg)")
            add("Volume: " + String.format(java.util.Locale.US, "%.2fx", volumeRatio) +
                " • momentum " + if (momentumAgrees) "supports" else "opposes")
            add("Projected next move: " + String.format(java.util.Locale.US, "$%.2f", projectedMove))
            add("Distance needed: " + String.format(java.util.Locale.US, "$%.2f", distanceToTarget))
            add("Estimated time: " + String.format(java.util.Locale.US, "%.1f min", minutesToTarget))
            add("Time remaining: " + String.format(java.util.Locale.US, "%.1f min", minutesRemaining))
            add("EMA $emaPeriod: $emaTrend")
        }.joinToString(" | ")
        return EarlySignalResult(label, targetPosition, evidence)
    }

    /** Groups consecutive one-minute close changes into directional impulse/pullback legs. */
    private fun buildLegs(candles: List<Candle>): List<Leg> {
        val result = mutableListOf<Leg>()
        if (candles.size < 2) return result
        var startIndex = 0
        var direction = 0
        for (index in 1 until candles.size) {
            val change = candles[index].close - candles[index - 1].close
            val nextDirection = when { change > 0.0 -> 1; change < 0.0 -> -1; else -> 0 }
            if (nextDirection == 0) continue
            if (direction == 0) {
                direction = nextDirection
                startIndex = index - 1
            } else if (nextDirection != direction) {
                addLeg(result, candles, startIndex, index - 1, direction)
                startIndex = index - 1
                direction = nextDirection
            }
        }
        if (direction != 0) addLeg(result, candles, startIndex, candles.lastIndex, direction)
        return result
    }

    private fun addLeg(result: MutableList<Leg>, candles: List<Candle>, start: Int, end: Int, direction: Int) {
        if (end <= start) return
        val move = abs(candles[end].close - candles[start].close)
        if (move <= 0.0) return
        val minutes = ((candles[end].openTime - candles[start].openTime).coerceAtLeast(60_000L) / 60_000.0)
        val volume = candles.subList(start + 1, end + 1).map { it.volume }.average()
        result.add(Leg(direction, move, minutes, volume))
    }

    private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

    private const val FIFTEEN_MINUTES_MS = 15L * 60L * 1000L
    private const val FINAL_FIVE_MINUTES_MS = 5L * 60L * 1000L
}
