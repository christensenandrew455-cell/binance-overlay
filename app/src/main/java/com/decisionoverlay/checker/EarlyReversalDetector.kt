package com.decisionoverlay.checker

/** Target-aware warning based on actual price velocity and estimated time to target. */
data class EarlySignalResult(
    val label: String,
    val targetPosition: String,
    val detail: String,
)

object EarlyReversalDetector {
    fun evaluate(
        candles: List<Candle>,
        momentum: List<Double>,
        ema7: List<Double>,
        targetPrice: Double?,
        nowMs: Long = System.currentTimeMillis(),
    ): EarlySignalResult {
        val elapsedInMarket = nowMs % FIFTEEN_MINUTES_MS
        val remainingMs = FIFTEEN_MINUTES_MS - elapsedInMarket
        if (remainingMs > FINAL_FIVE_MINUTES_MS || remainingMs <= 0L) {
            return EarlySignalResult(
                "WAIT • FINAL 5 MINUTES ONLY",
                "WAITING",
                "Directional signals unlock with 5 minutes remaining.",
            )
        }

        if (candles.size < 6 || momentum.size != candles.size || ema7.size != candles.size) {
            return EarlySignalResult("WAIT • COLLECTING DATA", "TARGET UNKNOWN", "Waiting for at least 6 one-minute candles.")
        }

        val lastIndex = candles.lastIndex
        val last = candles[lastIndex]
        // The latest Binance kline close updates during the active one-minute candle.
        val price = last.close
        if (targetPrice == null || !targetPrice.isFinite() || !price.isFinite()) {
            return EarlySignalResult("WAIT • TARGET UNAVAILABLE", "TARGET UNKNOWN", "Waiting for a valid live price and 15-minute target.")
        }

        val distance = targetPrice - price
        val targetPosition = when {
            distance < 0.0 -> "ABOVE TARGET"
            distance > 0.0 -> "BELOW TARGET"
            else -> "AT TARGET"
        }

        // Measure actual price movement across the latest five one-minute candles.
        // Small but sustained trends count too; no minimum dollar-move threshold.
        val recent = candles.takeLast(6)
        val first = recent.first()
        val elapsedMinutes = ((last.openTime - first.openTime).coerceAtLeast(1L)).toDouble() / 60_000.0
        val pricePerMinute = (price - first.close) / elapsedMinutes
        val minutesToTarget = if (pricePerMinute != 0.0 && pricePerMinute.isFinite()) {
            distance / pricePerMinute
        } else Double.POSITIVE_INFINITY
        val minutesRemaining = remainingMs / 60_000.0
        val canReachInTime = minutesToTarget > 0.0 &&
            minutesToTarget.isFinite() &&
            minutesToTarget <= minutesRemaining

        val momentumNow = momentum[lastIndex]
        val momentumPrevious = momentum[lastIndex - 1]
        val emaNow = ema7[lastIndex]
        val emaPrevious = ema7[lastIndex - 1]
        val histogramRising = momentumNow > momentumPrevious
        val histogramFalling = momentumNow < momentumPrevious
        val emaRising = emaNow > emaPrevious
        val emaFalling = emaNow < emaPrevious

        val rateText = String.format(java.util.Locale.US, "$%.2f/min", kotlin.math.abs(pricePerMinute))
        val etaText = if (minutesToTarget.isFinite() && minutesToTarget > 0.0) {
            String.format(java.util.Locale.US, "%.1f min to target", minutesToTarget)
        } else "not moving toward target"

        val label = when {
            canReachInTime && pricePerMinute > 0.0 -> "EARLY UP TREND"
            canReachInTime && pricePerMinute < 0.0 -> "EARLY DOWN TREND"
            distance > 0.0 && pricePerMinute > 0.0 -> "WAIT • UP TREND, TARGET TOO FAR"
            distance < 0.0 && pricePerMinute < 0.0 -> "WAIT • DOWN TREND, TARGET TOO FAR"
            distance > 0.0 -> "WAIT • BELOW TARGET"
            distance < 0.0 -> "WAIT • ABOVE TARGET"
            else -> "WAIT • AT TARGET"
        }

        val evidence = buildList {
            add("Actual price: " + String.format(java.util.Locale.US, "$%,.2f", price))
            add("Target: " + String.format(java.util.Locale.US, "$%,.2f", targetPrice))
            add("Price trend: " + (if (pricePerMinute > 0.0) "UP" else if (pricePerMinute < 0.0) "DOWN" else "FLAT") + " • $rateText")
            add("Estimated time: $etaText")
            add("Time remaining: " + String.format(java.util.Locale.US, "%.1f min", minutesRemaining))
            add("EMA 7: " + (if (emaRising) "rising" else if (emaFalling) "falling" else "flat") + " • MACD histogram: " + (if (histogramRising) "rising" else if (histogramFalling) "falling" else "flat"))
        }.joinToString(" | ")

        return EarlySignalResult(label, targetPosition, evidence)
    }

    private const val FIFTEEN_MINUTES_MS = 15L * 60L * 1000L
    private const val FINAL_FIVE_MINUTES_MS = 5L * 60L * 1000L
}
