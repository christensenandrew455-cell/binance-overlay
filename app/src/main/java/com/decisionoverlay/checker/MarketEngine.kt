package com.decisionoverlay.checker

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
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

    private var socketJob: Job? = null
    private var pollJob: Job? = null
    private var watchdogJob: Job? = null
    private var activeSocket: WebSocket? = null
    private var last5mEventAt = 0L
    private var lastVerdict: Verdict? = null

    fun start(rawSymbol: String) {
        if (!started.compareAndSet(false, true)) {
            updateSymbol(rawSymbol)
            return
        }
        symbol = normalize(rawSymbol) ?: "BTCUSDT"
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                logLocked("Engine start for $symbol")
                refreshAllLocked()
            }
            launchSockets()
            launchSlowPoll()
            launchFiveMinuteWatchdog()
        }
    }

    fun updateSymbol(rawSymbol: String) {
        scope.launch(Dispatchers.IO) {
            val next = normalize(rawSymbol)
            if (next == null) {
                mutex.withLock {
                    logLocked("Rejected symbol '$rawSymbol'. Use 5-20 letters or digits, for example BTCUSDT.")
                    publishLocked()
                }
                return@launch
            }
            if (next == symbol) return@launch
            symbol = next
            activeSocket?.close(1000, "symbol")
            mutex.withLock {
                candles.values.forEach { it.clear() }
                feeds.values.forEach { feed ->
                    feed.state = ConnectionState.NOT_WORKING
                    feed.detail = "Reloading $next"
                    feed.updatedAt = null
                    feed.lastError = null
                }
                last5mEventAt = 0L
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
        socketJob?.cancel()
        pollJob?.cancel()
        watchdogJob?.cancel()
        activeSocket?.cancel()
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }

    private fun launchSlowPoll() {
        pollJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(SLOW_POLL_MS)
                mutex.withLock {
                    logLocked("15-minute poll for 15m and 1h")
                    fetchLocked("15m", "REST 15m poll")
                    fetchLocked("1h", "REST 15m poll")
                }
            }
        }
    }

    private fun launchFiveMinuteWatchdog() {
        watchdogJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(WATCHDOG_MS)
                val stale = mutex.withLock { System.currentTimeMillis() - last5mEventAt > STALE_5M_MS }
                if (!stale) continue
                mutex.withLock {
                    logLocked("5m websocket stale — REST fallback")
                    fetchLocked("5m", "REST fallback")
                }
            }
        }
    }

    private fun launchSockets() {
        socketJob = scope.launch(Dispatchers.IO) {
            var backoff = 2_000L
            var hostIndex = 0
            while (isActive) {
                val current = symbol.lowercase(Locale.US)
                val root = BinanceClient.WS_ROOTS[hostIndex % BinanceClient.WS_ROOTS.size]
                val url = root + current + "@kline_5m"
                val closed = CompletableDeferred<Unit>()
                mutex.withLock { logLocked("WS connecting $url") }
                val request = Request.Builder().url(url).build()
                val socket = http.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        scope.launch(Dispatchers.IO) {
                            mutex.withLock {
                                logLocked("WS connected $url")
                                feeds.getValue("5m").detail = "WebSocket live"
                                publishLocked()
                            }
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        scope.launch(Dispatchers.IO) {
                            mutex.withLock { ingestLocked(text, url) }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        scope.launch(Dispatchers.IO) {
                            mutex.withLock {
                                val code = response?.code?.let { " HTTP $it" }.orEmpty()
                                logLocked("WS failure ${t.javaClass.simpleName}: ${t.message ?: "unknown"}$code")
                                val stale = System.currentTimeMillis() - last5mEventAt > STALE_5M_MS
                                if (stale) {
                                    feeds.getValue("5m").apply {
                                        state = ConnectionState.NOT_WORKING
                                        detail = "WebSocket down"
                                        lastError = t.message
                                    }
                                    publishLocked()
                                }
                                if (!closed.isCompleted) closed.complete(Unit)
                            }
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        scope.launch(Dispatchers.IO) {
                            mutex.withLock {
                                logLocked("WS closed $code ${reason.ifBlank { "" }}".trim())
                                if (!closed.isCompleted) closed.complete(Unit)
                            }
                        }
                    }
                })
                activeSocket = socket
                try {
                    closed.await()
                } finally {
                    socket.cancel()
                }
                if (!isActive) break
                hostIndex++
                mutex.withLock { logLocked("WS reconnect in ${backoff / 1000}s") }
                delay(backoff)
                val recent = mutex.withLock { System.currentTimeMillis() - last5mEventAt < 10_000 }
                backoff = if (recent) 2_000L else (backoff * 2).coerceAtMost(15_000L)
            }
        }
    }

    private fun ingestLocked(text: String, url: String) {
        try {
            val candle = client.parseKlineEvent(text, symbol) ?: return
            val list = candles.getValue("5m")
            val index = list.indexOfFirst { it.openTime == candle.openTime }
            if (index >= 0) list[index] = candle else list.add(candle)
            list.sortBy { it.openTime }
            while (list.size > 200) list.removeAt(0)
            last5mEventAt = System.currentTimeMillis()
            feeds.getValue("5m").apply {
                state = ConnectionState.CONNECTED
                detail = "WebSocket live tick"
                updatedAt = last5mEventAt
                lastError = null
            }
            publishLocked()
        } catch (error: Exception) {
            logLocked("WS parse error on $url: ${error.message}")
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
            result.notes.forEach { note -> logLocked("$interval host failed before fallback: $note") }
            candles.getValue(interval).apply {
                clear()
                addAll(result.candles)
            }
            val now = System.currentTimeMillis()
            if (interval == "5m") last5mEventAt = now
            val route = if (result.host == BinanceClient.REST_HOSTS.first()) "primary" else "fallback"
            feeds.getValue(interval).apply {
                state = ConnectionState.CONNECTED
                detail = "$via OK via $result.host ($route, ${result.candles.size} candles)"
                updatedAt = now
                lastError = null
            }
            logLocked("$interval $via OK ${result.candles.size} candles @ ${result.host} ($route)")
        } catch (error: Exception) {
            feeds.getValue(interval).apply {
                state = ConnectionState.NOT_WORKING
                detail = "Not Working"
                lastError = error.message
            }
            logLocked("$interval $via FAIL ${error.message}")
        }
        publishLocked()
    }

    private fun publishLocked() {
        val now = System.currentTimeMillis()
        age("5m", STALE_5M_MS, now)
        age("15m", SLOW_POLL_MS + 60_000, now)
        age("1h", SLOW_POLL_MS + 60_000, now)
        val five = Scorer.score5m(candles.getValue("5m"), feeds.getValue("5m").snapshot())
        val fifteen = Scorer.score15m(candles.getValue("15m"), feeds.getValue("15m").snapshot())
        val hour = Scorer.score1h(candles.getValue("1h"), feeds.getValue("1h").snapshot())
        val decision = Scorer.decide(five, fifteen, hour)
        if (decision.verdict != lastVerdict) {
            logLocked("Verdict ${decision.verdict}: ${decision.reason.take(180)}")
            lastVerdict = decision.verdict
        }
        val text = logs.joinToString("\n")
        onUpdate(EngineSnapshot(symbol, decision, text))
    }

    private fun age(interval: String, maxAgeMs: Long, now: Long) {
        val feed = feeds.getValue(interval)
        val updated = feed.updatedAt ?: return
        if (feed.state == ConnectionState.CONNECTED && now - updated > maxAgeMs) {
            feed.state = ConnectionState.NOT_WORKING
            feed.detail = "Stale (${(now - updated) / 1000}s since last good update)"
            feed.lastError = "No fresh $interval data inside ${maxAgeMs / 1000}s"
        }
    }

    private fun logLocked(message: String) {
        val stamp = LOG_CLOCK.format(LocalTime.now())
        logs.addLast("$stamp  $message")
        while (logs.size > 40) logs.removeFirst()
    }

    private fun normalize(raw: String): String? {
        val symbol = raw.trim().uppercase(Locale.US)
        return if (SYMBOL.matches(symbol)) symbol else null
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
        private const val WATCHDOG_MS = 15_000L
        private const val STALE_5M_MS = 30_000L
        private val SYMBOL = Regex("^[A-Z0-9]{5,20}$")
        private val LOG_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
