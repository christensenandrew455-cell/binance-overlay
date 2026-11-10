package com.decisionoverlay.checker

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MarketEngine(private val scope: CoroutineScope, private val onUpdate: (EngineSnapshot) -> Unit, initialStrictnessLevel: Int = 1) {
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val client = BinanceClient(http)
    private val mutex = Mutex()
    private val started = AtomicBoolean(false)
    private val logs = ArrayDeque<String>()
    private val candles = mutableListOf<Candle>()
    private val hourlyCandles = mutableListOf<Candle>()
    private val hourlyFeed = MutableFeed()
    private val feed = MutableFeed()
    @Volatile var symbol = "BTCUSDT"
        private set
    @Volatile private var strictnessLevel = initialStrictnessLevel.coerceIn(0, 4)
    private var targetPrice: Double? = null
    private var targetSession = -1L
    private var currentPrice: Double? = null
    private val jobs = mutableListOf<Job>()
    private var candidate = Verdict.DNT
    private var entryUntil = 0L
    private var lastVerdict = Verdict.DNT

    fun start(raw: String) {
        if (!started.compareAndSet(false, true)) { updateSymbol(raw); return }
        symbol = normalize(raw) ?: "BTCUSDT"
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                logLocked("Started 1-hour BTC steepness checker for $symbol; refreshed every minute")
                fetchOneMinuteLocked("STARTUP")
            }
            pollOneMinute()
        }
        jobs += scope.launch(Dispatchers.IO) { while (isActive) { delay(1000); mutex.withLock { publishLocked() } } }
    }

    fun updateSymbol(raw: String) {
        scope.launch(Dispatchers.IO) {
            val next = normalize(raw)
            if (next == null) { mutex.withLock { logLocked("Rejected symbol '$raw'"); publishLocked() }; return@launch }
            if (next == symbol) return@launch
            symbol = next
            mutex.withLock {
                candles.clear()
                hourlyCandles.clear()
                feed.reset("Reloading $next")
                hourlyFeed.reset("Reloading $next hourly candles")
                targetPrice = null
                targetSession = -1L
                currentPrice = null
                candidate = Verdict.DNT
                entryUntil = 0L
                lastVerdict = Verdict.DNT
                logLocked("Symbol changed to $next; waiting for its current 15-minute target")
                fetchOneMinuteLocked("SYMBOL CHANGE")
            }
        }
    }

    fun setStrictness(level: Int) {
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                strictnessLevel = level.coerceIn(0, 4)
                candidate = Verdict.DNT
                entryUntil = 0L
                logLocked("Strictness setting updated")
                publishLocked()
            }
        }
    }

    fun refreshNow() {
        scope.launch(Dispatchers.IO) { mutex.withLock { logLocked("Manual 1-minute refresh"); fetchOneMinuteLocked("MANUAL REFRESH") } }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun pollOneMinute() {
        jobs += scope.launch(Dispatchers.IO) {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(ONE - now % ONE + 2000L)
                if (isActive) mutex.withLock { fetchOneMinuteLocked("1M CANDLE CLOSE") }
            }
        }
    }

    private fun fetchOneMinuteLocked(via: String) {
        try {
            val result = client.fetchKlines(symbol, "1m", 150)
            result.notes.forEach { logLocked("1m fallback: $it") }
            try {
                val hourly = client.fetchKlines(symbol, "1h", 30)
                hourlyCandles.clear()
                hourlyCandles.addAll(hourly.candles.sortedBy { it.openTime })
                hourlyFeed.state = ConnectionState.CONNECTED
                hourlyFeed.detail = "1h OK via ${hourly.host} (${hourlyCandles.size} candles)"
                hourlyFeed.updatedAt = System.currentTimeMillis()
                hourlyFeed.lastError = null
            } catch (hourError: Exception) {
                hourlyFeed.state = ConnectionState.NOT_WORKING
                hourlyFeed.detail = "1-hour data unavailable"
                hourlyFeed.lastError = hourError.message
                logLocked("1h REST FAIL: ${hourError.message}")
            }
            val now = System.currentTimeMillis()
            val session = now / FIFTEEN * FIFTEEN
            val sessionCandle = result.candles.firstOrNull { it.openTime == session }
            if (sessionCandle != null) {
                if (targetSession != session || targetPrice != sessionCandle.open) {
                    targetSession = session
                    targetPrice = sessionCandle.open
                    candidate = Verdict.DNT
                    entryUntil = 0L
                    logLocked("New 15-minute target set from BTC price at " + formatTime(session) + ": " + fmtPrice(targetPrice))
                }
            } else if (targetSession != session) {
                targetSession = session
                targetPrice = null
                candidate = Verdict.DNT
                entryUntil = 0L
                logLocked("Waiting for the 1-minute candle that opens this 15-minute window")
            }

            val closed = result.candles.filter { it.closed }.sortedBy { it.openTime }.takeLast(100)
            // Use the newest exchange candle close as the live price; it updates during the current 1-minute candle.
            currentPrice = result.candles.maxByOrNull { it.openTime }?.close ?: closed.lastOrNull()?.close
            candles.clear()
            candles.addAll(closed)
            feed.state = ConnectionState.CONNECTED
            feed.detail = "$via OK via ${result.host} (${closed.size}/100 completed candles)"
            feed.updatedAt = now
            feed.lastError = null
            logLocked("1m REST OK: ${closed.size} completed candles; 1h candles: ${hourlyCandles.size}")
        } catch (error: Exception) {
            feed.state = ConnectionState.NOT_WORKING
            feed.detail = "Not Working"
            feed.lastError = error.message
            logLocked("1m REST FAIL: ${error.message}")
        }
        publishLocked()
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        val updated = feed.updatedAt
        if (updated != null && feed.state == ConnectionState.CONNECTED && now - updated > ONE * 2) {
            feed.state = ConnectionState.NOT_WORKING
            feed.detail = "Stale data"
            feed.lastError = "No fresh 1-minute candle inside 120s"
        }
        val status = feed.snapshot()
        val hourlyStatus = hourlyFeed.snapshot()
        if (hourlyStatus.updatedAt != null && hourlyStatus.state == ConnectionState.CONNECTED && now - hourlyStatus.updatedAt > ONE * 2) {
            hourlyFeed.state = ConnectionState.NOT_WORKING
            hourlyFeed.detail = "Stale 1-hour data"
            hourlyFeed.lastError = "No fresh hourly update inside 120s"
        }
        val ev = Scorer.evaluateHourlySteepness(hourlyCandles, hourlyFeed.snapshot())
        val one = Scorer.score1m(candles, status).copy(score = ev.score, price = currentPrice, components = listOf(ComponentScore("1h steepness", ev.score, ev.reason)))
        val context = one.copy(label = "Not used", interval = "none", components = emptyList())
        val verdict = if (ev.problems.isNotEmpty()) Verdict.DNT else ev.candidate
        if (verdict != candidate) {
            candidate = verdict
            logLocked("Hourly steepness signal changed to $verdict")
        }
        val reason = if (ev.problems.isNotEmpty()) {
            "DNT: waiting for 1-hour candle data and 24-return volatility history."
        } else ev.reason
        if (verdict != lastVerdict) { logLocked("Decision changed to $verdict (1m score ${signed(ev.score)})."); lastVerdict = verdict }
        val sessionStart = now / FIFTEEN * FIFTEEN
        val remaining = (sessionStart + FIFTEEN - now).coerceAtLeast(0L)
        val decision = Decision(verdict, reason, one, context, context, ev.problems, ev.score, 4, targetPrice, one.price, remaining, 0L, ev.buffer, ev.steepnessScore)
        onUpdate(EngineSnapshot(symbol, decision, logs.joinToString("\n")))
    }

    private fun logLocked(message: String) {
        logs.addLast("${CLOCK.format(LocalTime.now())}  $message")
        while (logs.size > 60) logs.removeFirst()
    }

    private fun normalize(value: String): String? = value.trim().uppercase(Locale.US).takeIf { SYMBOL.matches(it) }

    private class MutableFeed {
        var state = ConnectionState.NOT_WORKING
        var detail = "Waiting for first fetch"
        var updatedAt: Long? = null
        var lastError: String? = null
        fun snapshot() = FeedStatus(state, detail, updatedAt, lastError)
        fun reset(message: String) { state = ConnectionState.NOT_WORKING; detail = message; updatedAt = null; lastError = null }
    }

    companion object {
        private const val ONE = 60_000L
        private const val FIFTEEN = 900_000L
        private val SYMBOL = Regex("^[A-Z0-9]{5,20}$")
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
