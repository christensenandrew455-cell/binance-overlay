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
    private val feed = MutableFeed()
    @Volatile var symbol = "BTCUSDT"
        private set
    @Volatile private var strictnessLevel = initialStrictnessLevel.coerceIn(0, 4)
    private var targetPrice: Double? = null
    private var targetSession = -1L
    private val jobs = mutableListOf<Job>()
    private var candidate = Verdict.DNT
    private var entryUntil = 0L
    private var lastVerdict = Verdict.DNT

    fun start(raw: String) {
        if (!started.compareAndSet(false, true)) { updateSymbol(raw); return }
        symbol = normalize(raw) ?: "BTCUSDT"
        scope.launch(Dispatchers.IO) {
            mutex.withLock {
                logLocked("Started automatic 15-minute BTC target with 1-minute candles for $symbol")
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
                feed.reset("Reloading $next")
                targetPrice = null
                targetSession = -1L
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
            candles.clear()
            candles.addAll(closed)
            feed.state = ConnectionState.CONNECTED
            feed.detail = "$via OK via ${result.host} (${closed.size}/100 completed candles)"
            feed.updatedAt = now
            feed.lastError = null
            logLocked("1m REST OK: ${closed.size} completed candles; automatic target ${fmtPrice(targetPrice)}")
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
        val ev = Scorer.evaluatePullback(candles, status, strictnessLevel, targetPrice)
        val one = Scorer.score1m(candles, status).copy(score = ev.score, components = listOf(ComponentScore("1m target-aware trend", ev.score, ev.reason)))
        val context = one.copy(label = "Not used", interval = "none", components = emptyList())
        var verdict = if (ev.problems.isNotEmpty()) Verdict.DNT else ev.candidate
        if (verdict != Verdict.DNT && verdict != candidate) {
            candidate = verdict
            entryUntil = now + 60_000L
            logLocked("New $verdict signal opened for 60 seconds")
        }
        if (verdict == Verdict.DNT) { candidate = Verdict.DNT; entryUntil = 0L }
        if (verdict != Verdict.DNT && now >= entryUntil) verdict = Verdict.DNT
        val reason = when {
            ev.problems.isNotEmpty() -> "DNT: waiting for live 1-minute data, 100 completed candles, and the automatic 15-minute target."
            verdict == Verdict.DNT && candidate != Verdict.DNT -> "DNT: 60-second entry window expired."
            else -> ev.reason
        }
        if (verdict != lastVerdict) { logLocked("Decision changed to $verdict (1m score ${signed(ev.score)})."); lastVerdict = verdict }
        val sessionStart = now / FIFTEEN * FIFTEEN
        val remaining = (sessionStart + FIFTEEN - now).coerceAtLeast(0L)
        val decision = Decision(verdict, reason, one, context, context, ev.problems, ev.score, 3, targetPrice, one.price, remaining, (entryUntil - now).coerceAtLeast(0L), ev.buffer)
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
