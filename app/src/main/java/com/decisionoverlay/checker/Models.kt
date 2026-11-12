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
    val ema99Price: Double?,
    val trend: String,
    val currentVolume: Double?,
    val ema2Volume: Double?,
    val currentMomentum: Double?,
    val priceAnomaly: Boolean?,
    val volumeAnomaly: Boolean?,
    val momentumAnomaly: Boolean?,
    val candleCount: Int,
    val displayedCandleCount: Int,
    val updatedAt: Long?,
    val feedDetail: String,
    val lastError: String?,
)

data class EngineSnapshot(
    val symbol: String,
    val metrics: MetricSnapshot,
    val logText: String,
    val priceEmaSeries: List<Double> = emptyList(),
    val priceEma99Series: List<Double> = emptyList(),
    val volumeEmaSeries: List<Double> = emptyList(),
    val momentumSeries: List<Double> = emptyList(),
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
    return String.format(Locale.US, "%,. " .trim() + digits + "f", value)
}

private val clock = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    .withZone(java.time.ZoneId.systemDefault())

fun formatTime(epochMs: Long?): String =
    if (epochMs == null) "—" else clock.format(java.time.Instant.ofEpochMilli(epochMs))
