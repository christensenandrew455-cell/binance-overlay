package com.decisionoverlay.checker

import java.util.Locale

data class Candle(
    val openTime: Long,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Double,
    val closeTime: Long,
    val closed: Boolean,
)

data class MetricSnapshot(
    val currentPrice: Double?,
    val ema7Price: Double?,
    val currentVolume: Double?,
    val ema7Volume: Double?,
    val currentMomentum: Double?,
    val ema7Momentum: Double?,
    val priceAnomaly: Boolean?,
    val volumeAnomaly: Boolean?,
    val momentumAnomaly: Boolean?,
    val candleCount: Int,
    val updatedAt: Long?,
    val feedDetail: String,
    val lastError: String?,
) {
    fun displayText(): String = buildString {
        appendLine("1-MINUTE EMA 7")
        appendLine("Price EMA 7: ${fmtPrice(ema7Price)}")
        appendLine("Volume EMA 7: ${fmtNum(ema7Volume)}")
        appendLine("Momentum EMA 7: ${fmtNum(ema7Momentum)}")
        appendLine()
        appendLine("OUTLIER CHECK (2σ / last 14 candles)")
        appendLine("Price movement: ${anomalyText(priceAnomaly)}")
        appendLine("Volume: ${anomalyText(volumeAnomaly)}")
        appendLine("Momentum: ${anomalyText(momentumAnomaly)}")
        appendLine()
        appendLine("Current price: ${fmtPrice(currentPrice)}")
        appendLine("Current volume: ${fmtNum(currentVolume)}")
        appendLine("Current momentum: ${fmtNum(currentMomentum)}")
        appendLine("Candles: $candleCount / 100")
        appendLine(feedDetail)
        if (!lastError.isNullOrBlank()) appendLine("Error: $lastError")
        append("Updated: ${formatTime(updatedAt)}")
    }.trimEnd()

    private fun anomalyText(value: Boolean?): String = when (value) {
        true -> "UNUSUAL MOVE"
        false -> "Normal range"
        null -> "Collecting history"
    }
}

data class EngineSnapshot(
    val symbol: String,
    val metrics: MetricSnapshot,
    val logText: String,
)

fun fmtNum(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val magnitude = kotlin.math.abs(value)
    val digits = when {
        magnitude == 0.0 -> 2
        magnitude >= 100 -> 2
        magnitude >= 1 -> 3
        magnitude >= 0.01 -> 4
        else -> 6
    }
    return String.format(Locale.US, "%." + digits + "f", value)
}

fun fmtPrice(value: Double?): String {
    if (value == null || !value.isFinite()) return "—"
    val magnitude = kotlin.math.abs(value)
    val digits = when {
        magnitude >= 1000 -> 2
        magnitude >= 1 -> 4
        else -> 6
    }
    return String.format(Locale.US, "%,." + digits + "f", value)
}

private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(java.time.ZoneId.systemDefault())

fun formatTime(epochMs: Long?): String =
    if (epochMs == null) "—" else clock.format(java.time.Instant.ofEpochMilli(epochMs))
