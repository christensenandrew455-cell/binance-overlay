package com.decisionoverlay.checker

/** Target-aware early trend warning. It identifies developing pressure, not a guaranteed forecast. */
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
    ): EarlySignalResult {
        // Use the live 1-minute candle so warnings can appear before candle close.
        val indexes = candles.indices.toList().takeLast(12)
        if (indexes.size < 6 || momentum.size != candles.size || ema7.size != candles.size) {
            return EarlySignalResult("WAIT • COLLECTING DATA", "TARGET UNKNOWN", "Waiting for at least 6 one-minute candles.")
        }

        val lastIndex = indexes.last()
        val previousIndex = indexes[indexes.lastIndex - 1]
        val twoBackIndex = indexes[indexes.lastIndex - 2]
        val threeBackIndex = indexes[indexes.lastIndex - 3]
        val last = candles[lastIndex]
        val price = last.close
        if (targetPrice == null || !targetPrice.isFinite()) {
            return EarlySignalResult("WAIT • TARGET UNAVAILABLE", "TARGET UNKNOWN", "Waiting for the latest completed 15-minute target candle.")
        }

        val aboveTarget = price > targetPrice
        val belowTarget = price < targetPrice
        val targetPosition = when {
            aboveTarget -> "ABOVE TARGET"
            belowTarget -> "BELOW TARGET"
            else -> "AT TARGET"
        }

        val histNow = momentum[lastIndex]
        val histPrev = momentum[previousIndex]
        val histTwoBack = momentum[twoBackIndex]
        // A two-step slope change is an early hint; do not wait for a zero-line cross.
        val histFalling = histNow < histPrev && histPrev <= histTwoBack
        val histRising = histNow > histPrev && histPrev >= histTwoBack

        val emaNow = ema7[lastIndex]
        val emaThreeBack = ema7[threeBackIndex]
        val emaTurningDown = emaNow < emaThreeBack
        val emaTurningUp = emaNow > emaThreeBack

        val recent = indexes.takeLast(3).map { candles[it] }
        val downCandles = recent.count { it.close < it.open }
        val upCandles = recent.count { it.close > it.open }
        val priorVolumes = indexes.dropLast(1).takeLast(10).map { candles[it].volume }
        val averageVolume = priorVolumes.average().takeIf { priorVolumes.isNotEmpty() } ?: 0.0
        val volumeConfirm = averageVolume > 0.0 && last.volume >= averageVolume * 1.20
        val redVolumeConfirm = volumeConfirm && last.close < last.open
        val greenVolumeConfirm = volumeConfirm && last.close > last.open

        val downScore = (if (histFalling) 2 else 0) +
            (if (emaTurningDown) 1 else 0) +
            (if (downCandles >= 2) 1 else 0) +
            (if (redVolumeConfirm) 1 else 0)
        val upScore = (if (histRising) 2 else 0) +
            (if (emaTurningUp) 1 else 0) +
            (if (upCandles >= 2) 1 else 0) +
            (if (greenVolumeConfirm) 1 else 0)

        // Only alert for the direction that benefits from the current target position.
        // Keep this deliberately early: histogram slope plus one independent confirmation.
        val label = when {
            aboveTarget && histFalling && downScore >= 3 -> "EARLY DOWN TREND"
            belowTarget && histRising && upScore >= 3 -> "EARLY UP TREND"
            aboveTarget -> "WAIT • ABOVE TARGET"
            belowTarget -> "WAIT • BELOW TARGET"
            else -> "WAIT • AT TARGET"
        }

        val evidence = buildList {
            add("MACD histogram: " + (if (histRising) "building upward" else if (histFalling) "building downward" else "no clear slope change"))
            add("EMA 7: " + (if (emaTurningUp) "rising" else if (emaTurningDown) "falling" else "flat"))
            add("Recent candles: $upCandles up / $downCandles down")
            add("Volume: " + (if (volumeConfirm) "elevated" else "normal"))
            add("Scores: UP $upScore/5 • DOWN $downScore/5")
        }.joinToString(" | ")

        return EarlySignalResult(label, targetPosition, evidence)
    }
}
