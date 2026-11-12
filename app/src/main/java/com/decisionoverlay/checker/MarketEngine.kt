package com.decisionoverlay.checker

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
        log("Started Binance.US 1-minute EMA monitor for $symbol")
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
            val result = client.fetchKlines(symbol, "1m", 100)
            candles = result.candles.sortedBy { it.openTime }.takeLast(100)
            updatedAt = System.currentTimeMillis()
            lastError = null
            feedDetail = "Connected to Binance.US via ${result.host} • $source"
            log("Fetched ${candles.size} one-minute candles from ${result.host}")
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

        // Price uses EMA 7; volume and momentum use the faster EMA 3.
        val priceEmaSeries = Indicators.emaSeriesFromFirst(closes, 7)
        val volumeEmaSeries = Indicators.emaSeriesFromFirst(volumes, 3)
        val momentumEmaSeries = Indicators.emaSeriesFromFirst(momentum, 3)

        val latest = data.lastOrNull()
        val metrics = MetricSnapshot(
            currentPrice = latest?.close,
            ema7Price = priceEmaSeries.lastOrNull(),
            currentVolume = latest?.volume,
            ema3Volume = volumeEmaSeries.lastOrNull(),
            currentMomentum = momentum.lastOrNull(),
            ema3Momentum = momentumEmaSeries.lastOrNull(),
            priceAnomaly = isOutlier(priceEmaSeries),
            volumeAnomaly = isOutlier(volumeEmaSeries),
            momentumAnomaly = isOutlier(momentumEmaSeries),
            candleCount = data.size,
            updatedAt = updatedAt,
            feedDetail = feedDetail,
            lastError = lastError,
        )
        onUpdate(EngineSnapshot(symbol, metrics, logs.joinToString("\n"), priceEmaSeries.takeLast(60), volumeEmaSeries.takeLast(60), momentumEmaSeries.takeLast(60)))
    }

    // Compare the newest EMA value with the preceding 14 EMA values (sample standard deviation).
    // The current observation is excluded from its own baseline so a spike cannot hide itself.
    private fun isOutlier(values: List<Double>): Boolean? {
        if (values.size < 15) return null
        val baseline = values.dropLast(1).takeLast(14)
        val mean = baseline.average()
        val variance = baseline.sumOf { (it - mean) * (it - mean) } / (baseline.size - 1)
        val standardDeviation = kotlin.math.sqrt(variance)
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
        private val SYMBOL = Regex("^[A-Z0-9]{5,20}$")
    }
}
