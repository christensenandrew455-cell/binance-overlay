package com.decisionoverlay.checker

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.WebSocket
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
    @Volatile private var orderBook: OrderBookSnapshot? = null
    private val orderBookHistory = ArrayDeque<OrderBookSnapshot>()
    private val rawOrderBookSamples = ArrayDeque<OrderBookSnapshot>()
    @Volatile private var orderBookSocket: WebSocket? = null
    @Volatile private var lastOrderBookStreamAtMs = 0L
    @Volatile private var bookWindowStartedAtMs = 0L
    @Volatile private var orderBookWindowSeconds = 15
    private var orderBookReconnectJob: Job? = null

    @Volatile
    var symbol = "BTCUSDT"
        private set

    @Volatile
    var emaPeriod: Int = 3
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
        connectOrderBookStream()
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
        synchronized(orderBookHistory) { orderBookHistory.clear() }
        synchronized(rawOrderBookSamples) { rawOrderBookSamples.clear() }
        orderBook = null
        lastOrderBookStreamAtMs = 0L
        bookWindowStartedAtMs = 0L
        updatedAt = null
        lastError = null
        feedDetail = "Loading $next 1-minute candles"
        log("Symbol changed to $next")
        connectOrderBookStream()
        refreshNow()
    }

    fun updateOrderBookWindow(seconds: Int) {
        val next = seconds.coerceIn(15, 60).let { if (it <= 15) 15 else if (it <= 30) 30 else if (it <= 45) 45 else 60 }
        if (next == orderBookWindowSeconds) return
        orderBookWindowSeconds = next
        synchronized(rawOrderBookSamples) { rawOrderBookSamples.clear() }
        synchronized(orderBookHistory) { orderBookHistory.clear() }
        orderBook = null
        bookWindowStartedAtMs = 0L
        log("Order book collection window changed to ${next}s; collecting a fresh full window")
        publish()
    }

    fun updateEmaPeriod(period: Int) {
        val next = period.coerceIn(1, 10)
        if (next == emaPeriod) return
        emaPeriod = next
        log("Price EMA changed to EMA $next")
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
        orderBookReconnectJob?.cancel()
        orderBookReconnectJob = null
        orderBookSocket?.cancel()
        orderBookSocket = null
        started.set(false)
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    /**
     * Binance.US sends a fresh top-10 depth snapshot about every 100 ms.
     * Each output is a non-overlapping batch: collect continuously for the selected
     * 15/30/45/60 seconds, then publish one average and start the next full batch.
     */
    private fun connectOrderBookStream() {
        if (!started.get()) return
        val streamSymbol = symbol
        orderBookSocket?.cancel()
        orderBookSocket = client.openOrderBookStream(
            streamSymbol,
            onSnapshot = { sample ->
                if (!started.get() || symbol != streamSymbol) return@openOrderBookStream
                lastOrderBookStreamAtMs = sample.capturedAtMs
                synchronized(rawOrderBookSamples) {
                    // Start a fresh batch on the first live depth snapshot after start,
                    // a setting change, or a reconnect.
                    if (bookWindowStartedAtMs == 0L) {
                        rawOrderBookSamples.clear()
                        rawOrderBookSamples.addLast(sample)
                        bookWindowStartedAtMs = sample.capturedAtMs
                    } else {
                        rawOrderBookSamples.addLast(sample)
                        val elapsedMs = sample.capturedAtMs - bookWindowStartedAtMs
                        val requiredMs = orderBookWindowSeconds * 1_000L
                        if (elapsedMs >= requiredMs) {
                            // Publish only after a complete selected-duration collection.
                            val completedBatch = rawOrderBookSamples.toList()
                            val averaged = averageOrderBook(completedBatch)
                            if (averaged != null && completedBatch.size >= MIN_ORDER_BOOK_SAMPLES) {
                                orderBook = averaged
                                synchronized(orderBookHistory) {
                                    orderBookHistory.addLast(averaged)
                                    while (orderBookHistory.size > 60) orderBookHistory.removeFirst()
                                }
                                log("Completed ${orderBookWindowSeconds}s order book batch using ${completedBatch.size} depth snapshots")
                            } else {
                                log("Order book batch incomplete: ${completedBatch.size} samples; waiting for next full window")
                                orderBook = null
                            }
                            // The boundary snapshot begins the next non-overlapping batch.
                            rawOrderBookSamples.clear()
                            rawOrderBookSamples.addLast(sample)
                            bookWindowStartedAtMs = sample.capturedAtMs
                        }
                    }
                }
            },
            onDisconnected = {
                if (started.get() && symbol == streamSymbol) {
                    log("Order book stream disconnected; discarding incomplete collection window and reconnecting")
                    synchronized(rawOrderBookSamples) { rawOrderBookSamples.clear() }
                    synchronized(orderBookHistory) { orderBookHistory.clear() }
                    bookWindowStartedAtMs = 0L
                    orderBook = null
                    orderBookSocket = null
                    orderBookReconnectJob?.cancel()
                    orderBookReconnectJob = scope.launch(Dispatchers.IO) {
                        delay(3_000L)
                        if (started.get() && symbol == streamSymbol) connectOrderBookStream()
                    }
                }
            },
        )
        log("Connected to live $streamSymbol top-10 order book stream (100 ms updates)")
    }

    private fun averageOrderBook(samples: List<OrderBookSnapshot>): OrderBookSnapshot? {
        if (samples.isEmpty()) return null
        val count = samples.size.toDouble()
        val bidNear = samples.sumOf { it.nearBidNotional } / count
        val askNear = samples.sumOf { it.nearAskNotional } / count
        val totalNear = bidNear + askNear
        return OrderBookSnapshot(
            bidVolume = samples.sumOf { it.bidVolume } / count,
            askVolume = samples.sumOf { it.askVolume } / count,
            bestBid = samples.sumOf { it.bestBid } / count,
            bestAsk = samples.sumOf { it.bestAsk } / count,
            imbalance = if (totalNear > 0.0) (bidNear - askNear) / totalNear else 0.0,
            spread = samples.sumOf { it.spread } / count,
            capturedAtMs = samples.last().capturedAtMs,
            bidNotional = samples.sumOf { it.bidNotional } / count,
            askNotional = samples.sumOf { it.askNotional } / count,
            nearBidNotional = bidNear,
            nearAskNotional = askNear,
            smoothingWindowSeconds = orderBookWindowSeconds,
        )
    }

    private fun fetchAndPublish(source: String) {
        try {
            // Warm up EMA 99 with 150 candles, but only graph the newest 60.
            val result = client.fetchKlines(symbol, "1m", 150)
            candles = result.candles.sortedBy { it.openTime }.takeLast(150)

            // Never substitute a one-off REST snapshot for a completed timed batch.
            // If the stream is stale, clear the reading and wait for a fresh full window.
            if (lastOrderBookStreamAtMs == 0L ||
                System.currentTimeMillis() - lastOrderBookStreamAtMs > ORDER_BOOK_STALE_MS
            ) {
                orderBook = null
                log("Order book stream stale; waiting for a complete ${orderBookWindowSeconds}s live collection window")
            }
            updatedAt = System.currentTimeMillis()
            lastError = null
            feedDetail = "Connected to Binance.US • $source • live order book: " +
                if (lastOrderBookStreamAtMs > 0L &&
                    System.currentTimeMillis() - lastOrderBookStreamAtMs <= ORDER_BOOK_STALE_MS
                ) "100 ms stream / timed ${orderBookWindowSeconds}s batches" else "waiting for live depth stream"
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
        // Binance-style MACD histogram: 2 x (DIF - signal), with DIF = EMA12 - EMA26.
        val macdFast = Indicators.emaSeriesFromFirst(closes, 12)
        val macdSlow = Indicators.emaSeriesFromFirst(closes, 26)
        val macdDif = macdFast.zip(macdSlow) { fast, slow -> fast - slow }
        val macdSignal = Indicators.emaSeriesFromFirst(macdDif, 9)
        val momentum = macdDif.zip(macdSignal) { dif, signal -> 2.0 * (dif - signal) }
        val priceEma = Indicators.emaSeriesFromFirst(closes, emaPeriod)
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
        val target = data.lastOrNull { candle ->
            candle.closed && (candle.closeTime + 1L) % FIFTEEN_MINUTES_MS == 0L
        }
        val trend = overallTrend(closes, priceEma99)
        val displayedMomentum = momentum.takeLast(60)
        val marketEndTime = target?.closeTime?.plus(1L)?.plus(FIFTEEN_MINUTES_MS)
        val earlySignal = EarlyReversalDetector.evaluate(
            data, momentum, priceEma, target?.close,
            emaPeriod = emaPeriod,
            marketEndTimeMs = marketEndTime,
            orderBook = orderBook,
            orderBookHistory = synchronized(orderBookHistory) { orderBookHistory.toList() },
        )

        val metrics = MetricSnapshot(
            currentPrice = latest?.close,
            emaPrice = priceEma.lastOrNull(),
            emaPeriod = emaPeriod,
            ema99Price = priceEma99.lastOrNull(),
            trend = trend,
            currentVolume = latest?.volume,
            ema2Volume = volumeEma2.lastOrNull(),
            currentMomentum = momentum.lastOrNull(),
            targetPrice = target?.close,
            targetTime = target?.closeTime?.plus(1L),
            priceAnomaly = isOutlier(priceEma),
            volumeAnomaly = isOutlier(volumeEma2),
            momentumAnomaly = isOutlier(displayedMomentum),
            candleCount = data.size,
            displayedCandleCount = minOf(60, data.size),
            updatedAt = updatedAt,
            feedDetail = feedDetail,
            lastError = lastError,
            earlySignal = earlySignal.label,
            targetPosition = earlySignal.targetPosition,
            signalDetail = earlySignal.detail,
        )
        onUpdate(
            EngineSnapshot(
                symbol = symbol,
                metrics = metrics,
                logText = logs.joinToString("\n"),
                priceEmaSeries = priceEma.takeLast(60),
                priceEma99Series = priceEma99.takeLast(60),
                volumeBaselineSeries = volumeBaseline.takeLast(60),
                volumeSpikeSeries = volumeSpikes.takeLast(60),
                volumeSeries = volumes.takeLast(60),
                volumeUpSeries = data.takeLast(60).map { it.close >= it.open },
                momentumSeries = displayedMomentum,
                orderBookBidSeries = synchronized(orderBookHistory) { orderBookHistory.map { it.nearBidNotional } }.takeLast(60),
                orderBookAskSeries = synchronized(orderBookHistory) { orderBookHistory.map { it.nearAskNotional } }.takeLast(60),
                orderBookWindowSeconds = orderBookWindowSeconds,
                orderBookCollectionElapsedSeconds = if (bookWindowStartedAtMs > 0L)
                    ((System.currentTimeMillis() - bookWindowStartedAtMs) / 1_000L).toInt().coerceIn(0, orderBookWindowSeconds)
                else 0,
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
        private const val ORDER_BOOK_STALE_MS = 8_000L
        private const val MIN_ORDER_BOOK_SAMPLES = 20
        private val SYMBOL = Regex("^[A-Z0-9]{5,20}$")
    }
}
