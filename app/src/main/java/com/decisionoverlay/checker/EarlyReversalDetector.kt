package com.decisionoverlay.checker

/** Early warning filter; this is not a guaranteed price prediction. */
data class EarlySignalResult(
    val label: String,
    val targetPosition: String,
    val detail: String,
)

object EarlyReversalDetector {
    fun evaluate(candles: List<Candle>, momentum: List<Double>, ema7: List<Double>, targetPrice: Double?): EarlySignalResult {
        // Include the currently forming 1-minute candle so early warnings can update between closes.
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
        val targetPosition = when {
            targetPrice == null -> "TARGET UNKNOWN"
            price > targetPrice -> "ABOVE TARGET"
            price < targetPrice -> "BELOW TARGET"
            else -> "AT TARGET"
        }

        // A histogram slope turn can appear before the histogram crosses zero.
        val histNow = momentum[lastIndex]
        val histPrev = momentum[previousIndex]
        val histTwoBack = momentum[twoBackIndex]
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
        val volumeConfirm = averageVolume > 0.0 && last.volume >= averageVolume * 1.25
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

        val label = when {
            last.closed && downScore >= 4 && price < emaNow -> "DOWN CONFIRMED"
            last.closed && upScore >= 4 && price > emaNow -> "UP CONFIRMED"
            downScore >= 3 && histFalling -> "EARLY DOWN"
            upScore >= 3 && histRising -> "EARLY UP"
            else -> "WAIT • NO EARLY REVERSAL"
        }

        val evidence = buildList {
            add("MACD histogram: " + (if (histRising) "turning up" else if (histFalling) "turning down" else "no clear turn"))
            add("EMA 7: " + (if (emaTurningUp) "rising" else if (emaTurningDown) "falling" else "flat"))
            add("Last 3 candles (latest may still be forming): $upCandles up / $downCandles down")
            add("Volume: " + (if (volumeConfirm) "elevated" else "normal"))
            add("Early scores: UP $upScore/5 • DOWN $downScore/5")
        }.joinToString(" | ")

        return EarlySignalResult(label, targetPosition, evidence)
    }
}
