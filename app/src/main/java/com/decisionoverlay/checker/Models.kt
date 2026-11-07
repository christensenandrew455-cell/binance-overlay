package com.decisionoverlay.checker

import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class Verdict {
    UP,
    DOWN,
    DNT,
}

enum class ConnectionState {
    CONNECTED,
    NOT_WORKING,
}

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

data class ComponentScore(
    val name: String,
    val points: Int,
    val detail: String,
)

data class FeedStatus(
    val state: ConnectionState,
    val detail: String,
    val updatedAt: Long?,
    val lastError: String?,
) {
    companion object {
        fun waiting() = FeedStatus(
            state = ConnectionState.NOT_WORKING,
            detail = "Waiting for first fetch",
            updatedAt = null,
            lastError = null,
        )
    }
}

data class TimeframeScore(
    val label: String,
    val interval: String,
    val score: Int,
    val components: List<ComponentScore>,
    val price: Double?,
    val ema9: Double?,
    val ema21: Double?,
    val rsi: Double?,
    val macdLine: Double?,
    val macdSignal: Double?,
    val macdHist: Double?,
    val candleBias: String,
    val volumeRatio: Double?,
    val connection: ConnectionState,
    val connectionDetail: String,
    val updatedAt: Long?,
    val lastError: String?,
    val candleCount: Int,
) {
    fun headerStatus(): String {
        val link = if (connection == ConnectionState.CONNECTED) "Connected" else "Not Working"
        return "${signed(score)}   $link"
    }

    fun metricsText(): String = buildString {
        val link = if (connection == ConnectionState.CONNECTED) "Connected" else "Not Working"
        appendLine("POINTS: " + signed(score))
        appendLine("Binance: $link")
        appendLine(connectionDetail)
        if (!lastError.isNullOrBlank()) appendLine("Last error: $lastError")
        appendLine("Last fetch: ${formatTime(updatedAt)}")
        appendLine("Completed candles: $candleCount")
        appendLine("Price: ${fmtPrice(price)}")
        when (interval) {
            "1h" -> { appendLine("EMA20: ${fmtNum(ema9)}"); appendLine("RSI(14): ${fmtNum(rsi)}") }
            "5m" -> { appendLine("EMA8: ${fmtNum(ema9)}"); appendLine("MACD: ${fmtNum(macdLine)}"); appendLine("Signal: ${fmtNum(macdSignal)}") }
            "15m" -> { appendLine("MACD: ${fmtNum(macdLine)}"); appendLine("Signal: ${fmtNum(macdSignal)}"); appendLine("Volume / 20 SMA: " + (volumeRatio?.let { String.format(Locale.US, "%.2fx", it) } ?: "—")) }
        }
        if (components.isEmpty()) {
            append("No component scores yet.")
        } else {
            components.forEach { component ->
                appendLine("• ${component.name} ${signed(component.points)} — ${component.detail}")
            }
        }
    }.trimEnd()
}

data class Decision(
    val verdict: Verdict,
    val reason: String,
    val five: TimeframeScore,
    val fifteen: TimeframeScore,
    val hour: TimeframeScore,
    val problems: List<String> = emptyList(),
    val totalScore: Int = 0,
    val dntThreshold: Int = Scorer.DEFAULT_DNT_THRESHOLD,
)

data class EngineSnapshot(
    val symbol: String,
    val decision: Decision,
    val logText: String,
)

fun signed(points: Int): String = if (points > 0) "+$points" else points.toString()

fun fmtNum(value: Double?): String {
    if (value == null || value.isNaN()) return "—"
    val magnitude = kotlin.math.abs(value)
    val digits = when {
        magnitude == 0.0 -> 2
        magnitude >= 100 -> 2
        magnitude >= 1 -> 3
        magnitude >= 0.01 -> 4
        else -> 6
    }
    return String.format(Locale.US, "%.${digits}f", value)
}

fun fmtPrice(value: Double?): String {
    if (value == null || value.isNaN()) return "—"
    val magnitude = kotlin.math.abs(value)
    val digits = when {
        magnitude >= 1000 -> 2
        magnitude >= 1 -> 4
        else -> 6
    }
    return String.format(Locale.US, "%,.${digits}f", value)
}

private val clock: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())

fun formatTime(epochMs: Long?): String = if (epochMs == null) "—" else clock.format(Instant.ofEpochMilli(epochMs))
