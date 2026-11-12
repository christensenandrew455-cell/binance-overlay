package com.decisionoverlay.checker

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MarketEngine(
    private val scope: CoroutineScope,
    private val onUpdate: (EngineSnapshot) -> Unit,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val client = BinanceClient(http)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val logs = ArrayDeque<String>()
    private var candles = emptyList<Candle>()

    @Volatile
    var symbol = "BTCUSDT"
        private set

    private var lastError: String? = null
    private var feedDetail = "Waiting for Binance.US 1-minute candles"
    private var updatedAt: Long? = null
    private var fetchJob: Job? = null

    fun start(raw: String) {
        if (!started.compareAndSet(false, true)) {
            updateSymbol(raw)
            return
        }
        symbol = normalize(raw) ?: "BTCUSDT"
        log("Started Binance.US 1-minute monitor for $symbol")
        fetchJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                mutex.withLock { fetchAndPublish("AUTO") }
                delay(5_000L)
            }
        }
    }

    fun updateSymbol(raw: String) {
        val next = normalize(raw) ?: return
        if (next == symbol) {
            refreshNow()
            return
        }
        symbol = next
        candles = emptyList()
        updatedAt = null
        lastError = null
        feedDetail = "Loading $next 1-minute candles"
        log("Symbol changed to $next")
        refreshNow()
    }

    fun refreshNow() {
        if (!started.get()) return
        scope.launch(Dispatchers.IO) {
            mutex.withLock { fetchAndPublish("MANUAL REFRESH") }
        }
    }

    fun stop() {
        fetchJob?.cancel()
        fetchJob = null
        started.set(false)
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun fetchAndPublish(source: String) {
        try {
            // Warm up EMA 99 with 150 candles, but only graph the newest 60.
            val result = client.fetchKlines(symbol, "1m", 150)
            candles = result.candles.sortedBy { it.openTime }.takeLast(150)
            updatedAt = System.currentTimeMillis()
            lastError = null
            feedDetail = "Connected to Binance.US via ${result.host} • $source"
            log("Fetched ${candles.size} one-minute candles; displaying latest 60")
            result.notes.forEach { log("Fallback: $it") }
        } catch (error: Exception) {
            lastError = error.message ?: error.javaClass.simpleName
            feedDetail = "Binance.US feed unavailable"
            log("FETCH ERROR: $lastError")
        }
        publish()
    }

    private fun publish() {
        val data = candles
        val closes = data.map { it.close }
        val volumes = data.map { it.volume }
        val momentum = closes.zipWithNext { previous, current -> current - previous }
        val priceEma7 = Indicators.emaSeriesFromFirst(closes, 7)
        val priceEma99 = Indicators.emaSeriesFromFirst(closes, 99)
        val volumeEma2 = Indicators.emaSeriesFromFirst(volumes, 2)
        val volumeBaseline = rollingAverage(volumes, 14)
        val volumeSpikes = volumes.mapIndexed { index, volume ->
            if (index < 14) null
            else {
                val sample = volumes.subList(index - 14, index)
                val mean = sample.average()
                val variance = sample.sumOf { (it - mean) * (it - mean) } / (sample.size - 1)
                val threshold = mean + 2.0 * sqrt(variance)
                if (volume > threshold) volume else null
            }
        }
        val latest = data.lastOrNull()
        // A checkpoint is the close of the 1-minute candle ending exactly at :00/:15/:30/:45.
        // If launched mid-interval, this deliberately keeps the previous checkpoint target.
        val target = data.lastOrNull { candle ->
            candle.closed && (candle.closeTime + 1L) % FIFTEEN_MINUTES_MS == 0L
        }
        val trend = overallTrend(closes, priceEma99)
        val displayedMomentum = momentum.takeLast(60)

        val metrics = MetricSnapshot(
            currentPrice = latest?.close,
            ema7Price = priceEma7.lastOrNull(),
            ema99Price = priceEma99.lastOrNull(),
            trend = trend,
            currentVolume = latest?.volume,
            ema2Volume = volumeEma2.lastOrNull(),
            currentMomentum = momentum.lastOrNull(),
            targetPrice = target?.close,
            targetTime = target?.closeTime?.plus(1L),
            priceAnomaly = isOutlier(priceEma7),
            volumeAnomaly = isOutlier(volumeEma2),
            momentumAnomaly = isOutlier(displayedMomentum),
            candleCount = data.size,
            displayedCandleCount = minOf(60, data.size),
            updatedAt = updatedAt,
            feedDetail = feedDetail,
            lastError = lastError,
        )
        onUpdate(
            EngineSnapshot(
                symbol = symbol,
                metrics = metrics,
                logText = logs.joinToString("\n"),
                priceEmaSeries = priceEma7.takeLast(60),
                priceEma99Series = priceEma99.takeLast(60),
                volumeBaselineSeries = volumeBaseline.takeLast(60),
                volumeSpikeSeries = volumeSpikes.takeLast(60),
                momentumSeries = displayedMomentum,
            )
        )
    }

    private fun rollingAverage(values: List<Double>, window: Int): List<Double> =
        values.indices.map { index ->
            val start = (index - window + 1).coerceAtLeast(0)
            values.subList(start, index + 1).average()
        }

    private fun overallTrend(closes: List<Double>, ema99: List<Double>): String {
        if (closes.isEmpty() || ema99.size < 6) return "NEUTRAL"
        val price = closes.last()
        val currentAverage = ema99.last()
        val priorAverage = ema99[ema99.lastIndex - 5]
        return when {
            price > currentAverage && currentAverage > priorAverage -> "UP"
            price < currentAverage && currentAverage < priorAverage -> "DOWN"
            else -> "NEUTRAL"
        }
    }

    private fun isOutlier(values: List<Double>): Boolean? {
        if (values.size < 15) return null
        val baseline = values.dropLast(1).takeLast(14)
        val mean = baseline.average()
        val variance = baseline.sumOf { (it - mean) * (it - mean) } / (baseline.size - 1)
        val standardDeviation = sqrt(variance)
        if (standardDeviation <= 0.0) return values.last() != mean
        return kotlin.math.abs(values.last() - mean) > 2.0 * standardDeviation
    }

    private fun log(message: String) {
        logs.addLast("${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}  $message")
        while (logs.size > 50) logs.removeFirst()
    }

    private fun normalize(value: String): String? =
        value.trim().uppercase(Locale.US).takeIf { SYMBOL.matches(it) }

    companion object {
        private const val FIFTEEN_MINUTES_MS = 15L * 60L * 1000L
        private val SYMBOL = Regex("^[A-Z0-9]{5,20}$")
    }
}
