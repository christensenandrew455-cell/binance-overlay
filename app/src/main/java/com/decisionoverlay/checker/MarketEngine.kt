package com.decisionoverlay.checker

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter
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
    private val candles = linkedMapOf(
        "5m" to mutableListOf<Candle>(),
        "15m" to mutableListOf<Candle>(),
        "1h" to mutableListOf<Candle>(),
    )
    private val feeds = linkedMapOf(
        "5m" to MutableFeed(),
        "15m" to MutableFeed(),
        "1h" to MutableFeed(),
    )

    @Volatile
    var symbol: String = "BTCUSDT"
        private set

    private var fiveMinuteJob: Job? = null
    private var slowJob: Job? = null
    private var lastVerdict: Verdict? = null

    fun start(rawSymbol: String) {
        if (!started.compareAndSet(false, true)) {
            updateSymbol(rawSymbol)
            return
        }
        symbol = normalize(rawSymbol) ?: "BTCUSDT"
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                logLocked("Started public Binance REST feed for $symbol (no API key required)")
                refreshAllLocked()
            }
            launchPolls()
        }
    }

    fun updateSymbol(rawSymbol: String) {
        scope.launch(Dispatchers.IO) {
            val next = normalize(rawSymbol)
            if (next == null) {
                mutex.withLock {
                    logLocked("Rejected symbol '$rawSymbol'. Use a Binance spot symbol such as BTCUSDT.")
                    publishLocked()
                }
                return@launch
            }
            if (next == symbol) return@launch
            symbol = next
            mutex.withLock {
                candles.values.forEach { it.clear() }
                feeds.values.forEach { feed ->
                    feed.state = ConnectionState.NOT_WORKING
                    feed.detail = "Reloading $next"
                    feed.updatedAt = null
                    feed.lastError = null
                }
                lastVerdict = null
                logLocked("Symbol changed to $next")
                refreshAllLocked()
            }
        }
    }

    fun refreshNow() {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                logLocked("Manual refresh")
                refreshAllLocked()
            }
        }
    }

    fun stop() {
        fiveMinuteJob?.cancel()
        slowJob?.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun launchPolls() {
        fiveMinuteJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(FIVE_MINUTE_POLL_MS)
                mutex.withLock { fetchLocked("5m", "REST") }
            }
        }
        slowJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SLOW_POLL_MS)
                mutex.withLock {
                    logLocked("15-minute refresh: fetching 15m and 1h candles")
                    fetchLocked("15m", "REST")
                    fetchLocked("1h", "REST")
                }
            }
        }
    }

    private fun refreshAllLocked() {
        fetchLocked("5m", "REST")
        fetchLocked("15m", "REST")
        fetchLocked("1h", "REST")
    }

    private fun fetchLocked(interval: String, via: String) {
        try {
            val result = client.fetchKlines(symbol, interval, 150)
            result.notes.forEach { note -> logLocked("$interval host fallback: $note") }
            candles.getValue(interval).apply {
                clear()
                addAll(result.candles)
            }

            val now = System.currentTimeMillis()
            val route = if (result.host == BinanceClient.REST_HOSTS.first()) "primary" else "fallback"
            feeds.getValue(interval).apply {
                state = ConnectionState.CONNECTED
                detail = "$via OK via " + result.host + " (" + route + ", " +
                    result.candles.size + " candles)"
                updatedAt = now
                lastError = null
            }
            logLocked("$interval REST OK: " + result.candles.size + " candles via " +
                result.host + " (" + route + ")")
        } catch (error: Exception) {
            feeds.getValue(interval).apply {
                state = ConnectionState.NOT_WORKING
                detail = "Not Working"
                lastError = error.message
            }
            logLocked("$interval REST FAIL: " + error.message)
        }
        publishLocked()
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        age("5m", FIVE_MINUTE_POLL_MS * 3, now)
        age("15m", SLOW_POLL_MS + 60_000, now)
        age("1h", SLOW_POLL_MS + 60_000, now)

        val five = Scorer.score5m(candles.getValue("5m"), feeds.getValue("5m").snapshot())
        val fifteen = Scorer.score15m(candles.getValue("15m"), feeds.getValue("15m").snapshot())
        val hour = Scorer.score1h(candles.getValue("1h"), feeds.getValue("1h").snapshot())
        val decision = Scorer.decide(five, fifteen, hour)

        if (decision.verdict != lastVerdict) {
            logLocked("Overall verdict " + decision.verdict + ": " + decision.reason.take(180))
            lastVerdict = decision.verdict
        }

        onUpdate(EngineSnapshot(symbol, decision, logs.joinToString("\n")))
    }

    private fun age(interval: String, maxAgeMs: Long, now: Long) {
        val feed = feeds.getValue(interval)
        val updated = feed.updatedAt ?: return
        if (feed.state == ConnectionState.CONNECTED && now - updated > maxAgeMs) {
            feed.state = ConnectionState.NOT_WORKING
            feed.detail = "Stale (" + (now - updated) / 1000 + "s since last good update)"
            feed.lastError = "No fresh $interval data inside " + maxAgeMs / 1000 + "s"
        }
    }

    private fun logLocked(message: String) {
        val stamp = LOG_CLOCK.format(LocalTime.now())
        logs.addLast("$stamp  $message")
        while (logs.size > 40) logs.removeFirst()
    }

    private fun normalize(raw: String): String? {
        val value = raw.trim().uppercase(Locale.US)
        return if (SYMBOL.matches(value)) value else null
    }

    private class MutableFeed {
        var state: ConnectionState = ConnectionState.NOT_WORKING
        var detail: String = "Waiting for first fetch"
        var updatedAt: Long? = null
        var lastError: String? = null

        fun snapshot() = FeedStatus(state, detail, updatedAt, lastError)
    }

    companion object {
        const val SLOW_POLL_MS = 15 * 60 * 1000L
        private const val FIVE_MINUTE_POLL_MS = 15_000L
        private val SYMBOL = Regex("^[A-Z0-9]{5,20}$")
        private val LOG_CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
