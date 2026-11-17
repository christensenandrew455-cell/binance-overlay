package com.decisionoverlay.checker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Historical simulation, explicitly NOT a probability estimator and never
 * places trades. Compare the EXACT SAME market/observation for all 4 windows.
 * No future candle is used by the ACM formula or the trigger.
 */
class BtcAcmBacktestService : Service() {
    companion object {
        const val PREFS = "btc_acm_four_way_v1"
        const val START = "btc.acm.test.START"
        const val STOP = "btc.acm.test.STOP"
        private const val PROGRESS_CHANNEL = "btc_acm_scan_v1"
        private const val RESULTS_CHANNEL = "btc_acm_results_v1"
        private const val FOREGROUND_ID = 9234
        private const val NOTIFY_ID = 9235
        private const val MAX_DAYS = 120
    }

    private val minute = BtcAcmMath.MINUTE_MS
    private val quarter = 15L * minute
    private val zone = ZoneId.of("America/New_York")
    private val running = AtomicBoolean(false)
    private val cancel = AtomicBoolean(false)
    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private data class Bucket(
        val window: Int,
        val elapsed: Int,
        val side: Int,
        var eligible: Int = 0,
        var triggered: Int = 0,
        var stay: Int = 0,
        var cross: Int = 0,
        var tie: Int = 0,
        var touch: Int = 0,
        var acmSum: Double = 0.0,
        var gapSum: Double = 0.0
    ) {
        val noTouch get() = triggered - touch
        val stayPct get() = if (triggered == 0) Double.NaN
            else 100.0 * stay / triggered
        val noTouchPct get() = if (triggered == 0) Double.NaN
            else 100.0 * noTouch / triggered
        val frequencyPct get() = if (eligible == 0) Double.NaN
            else 100.0 * triggered / eligible
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            PROGRESS_CHANNEL, "BTC ACM backtest progress", NotificationManager.IMPORTANCE_LOW
        ))
        manager.createNotificationChannel(NotificationChannel(
            RESULTS_CHANNEL, "BTC ACM backtest results", NotificationManager.IMPORTANCE_DEFAULT
        ))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            cancel.set(true)
            if (running.get()) {
                save("stopping", "Stopping after current Binance.US request.")
            } else {
                save("stopped", "The backtest is stopped.")
                stopSelf()
            }
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_NOT_STICKY
        cancel.set(false)
        if (Build.VERSION.SDK_INT >= 29)
            startForeground(FOREGROUND_ID, foreground("Starting four ACM backtests"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(FOREGROUND_ID, foreground("Starting four ACM backtests"))
        save("running", "Preparing BTC 1-minute history. You can turn the screen off.", "")
        Thread {
            val lock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "decisionoverlay:btcAcmBacktest")
            try {
                lock.acquire(3L * 60L * 60L * 1000L)
                val output = scan()
                if (cancel.get()) {
                    save("stopped", "Backtest stopped: partial results below.", output)
                    finished("BTC ACM scan stopped", "Partial results are available.")
                } else {
                    save("done", "Four-way BTC ACM backtest complete.", output)
                    finished("BTC ACM backtest finished", "Compare 3, 15, 30 and 60 candles.")
                }
            } catch (e: Exception) {
                if (cancel.get()) {
                    save("stopped", "BTC ACM backtest stopped.")
                } else {
                    val msg = (e.message ?: e.javaClass.simpleName).take(240)
                    save("error", "BTC ACM backtest error: " + msg)
                    finished("BTC ACM scan failed", msg.take(100))
                }
            } finally {
                if (lock.isHeld) lock.release()
                running.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_NOT_STICKY
    }

    private fun save(phase: String, status: String, report: String? = null) {
        val editor = getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString("phase", phase).putString("status", status)
        if (report != null) editor.putString("report", report)
        editor.apply()
    }

    private fun foreground(message: String): Notification {
        val open = PendingIntent.getActivity(this, FOREGROUND_ID,
            Intent(this, BtcAcmBacktestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, PROGRESS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC ACM 3/15/30/60 simulation")
            .setContentText(message)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true).build()
    }

    private fun progress(message: String) {
        save("running", message)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(FOREGROUND_ID, foreground(message))
    }

    private fun finished(title: String, body: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(this, NOTIFY_ID,
            Intent(this, BtcAcmBacktestActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFY_ID, NotificationCompat.Builder(this, RESULTS_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title).setContentText(body)
                .setContentIntent(open).setAutoCancel(true).build())
    }

    private fun downloadDay(day: LocalDate): Map<Long, BtcAcmMath.Candle> {
        // Warm up from 9:00 so even the first 10:00 observation has
        // 60 completed candles available. Never fetch past 23:00 NY.
        val first = day.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        val last = day.atTime(23, 0).atZone(zone).toInstant().toEpochMilli() - 1L
        val url = "https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
            "&interval=1m&startTime=" + first + "&endTime=" + last + "&limit=1000"
        for (attempt in 1..3) {
            if (cancel.get()) return emptyMap()
            try {
                val payload = http.newCall(Request.Builder().url(url).get().build())
                    .execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        if (!response.isSuccessful)
                            error("Binance.US HTTP " + response.code + ": " + body.take(120))
                        JSONArray(body)
                    }
                val items = HashMap<Long, BtcAcmMath.Candle>()
                for (i in 0 until payload.length()) {
                    val candle = payload.getJSONArray(i)
                    val result = BtcAcmMath.Candle(
                        candle.getLong(0),
                        candle.getDouble(1), candle.getDouble(2),
                        candle.getDouble(3), candle.getDouble(4)
                    )
                    if (result.valid()) items[result.at] = result
                }
                return items
            } catch (e: Exception) {
                if (attempt == 3) throw e
                Thread.sleep(attempt * 1200L)
            }
        }
        return emptyMap()
    }

    private fun scan(): String {
        val buckets = BtcAcmMath.WINDOWS.flatMap { window ->
            (BtcAcmMath.FIRST_ELAPSED..BtcAcmMath.LAST_ELAPSED).flatMap { elapsed ->
                listOf(Bucket(window, elapsed, 1), Bucket(window, elapsed, -1))
            }
        }
        val byKey = buckets.associateBy { Triple(it.window, it.elapsed, it.side) }
        var day = LocalDate.now(zone).minusDays(1) // only fully finished days
        var daysScanned = 0
        var marketsScanned = 0
        var missingMarkets = 0
        var validObservations = 0
        var emptyDays = 0

        while (daysScanned < MAX_DAYS && !cancel.get()) {
            val candles = downloadDay(day)
            val from = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
            day = day.minusDays(1)
            daysScanned++
            if (candles.isEmpty()) {
                emptyDays++
                if (emptyDays >= 14) break
                continue
            }
            emptyDays = 0
            // Every session begins at :00/:15/:30/:45 and ends by 23:00 NY.
            for (offset in 0 until 52) {
                if (cancel.get()) break
                val market = from + offset * quarter
                val target = candles[market - minute]?.close
                val ending = candles[market + 14L * minute]?.close
                if (target == null || target <= 0.0 || ending == null ||
                    (0..14).any { candles[market + it * minute] == null }
                ) {
                    missingMarkets++
                    continue
                }
                marketsScanned++
                for (elapsed in BtcAcmMath.FIRST_ELAPSED..BtcAcmMath.LAST_ELAPSED) {
                    val observedAt = market + (elapsed - 1L) * minute
                    val spot = candles[observedAt]!!.close
                    val side = when {
                        spot > target -> 1
                        spot < target -> -1
                        else -> 0
                    }
                    if (side == 0) continue
                    // All 4 windows must have complete data; this guarantees
                    // an identical comparison population for each method.
                    val averages = BtcAcmMath.WINDOWS.associateWith {
                        BtcAcmMath.averageRange(candles, observedAt, it)
                    }
                    if (averages.values.any { it == null }) continue
                    validObservations++
                    val remaining = 15 - elapsed
                    val future = (elapsed..14).map {
                        candles[market + it * minute]!!
                    }
                    val outcome = BtcAcmMath.outcome(side, target, ending, future)
                    val gap = abs(BtcAcmMath.percentFromTarget(spot, target))
                    for (window in BtcAcmMath.WINDOWS) {
                        val avg = averages[window]!!
                        val bucket = byKey.getValue(Triple(window, elapsed, side))
                        bucket.eligible++
                        if (!BtcAcmMath.triggered(spot, target, avg, remaining))
                            continue
                        bucket.triggered++
                        bucket.acmSum += avg
                        bucket.gapSum += gap
                        if (outcome.stay) bucket.stay++
                        else if (outcome.tied) bucket.tie++
                        else bucket.cross++
                        if (outcome.touched) bucket.touch++
                    }
                }
            }
            if (daysScanned == 1 || daysScanned % 5 == 0 || daysScanned == MAX_DAYS) {
                val count = buckets.sumOf { it.triggered }
                val state = "Scanned " + daysScanned + "/" + MAX_DAYS +
                    " days; complete markets " + marketsScanned +
                    "; ACM threshold events " + count
                progress(state)
                if (daysScanned % 10 == 0 || daysScanned == MAX_DAYS) {
                    save("running", state, report(
                        buckets, daysScanned, marketsScanned,
                        missingMarkets, validObservations, false))
                }
            }
            if (!cancel.get()) Thread.sleep(100L)
        }
        return report(buckets, daysScanned, marketsScanned,
            missingMarkets, validObservations, !cancel.get())
    }

    private fun fmt(pct: Double): String =
        if (!pct.isFinite()) "N/A" else String.format(Locale.US, "%.1f%%", pct)

    private fun report(
        buckets: List<Bucket>, days: Int, markets: Int, missing: Int,
        observations: Int, complete: Boolean
    ): String = buildString {
        appendLine("BTC — 3 / 15 / 30 / 60 CANDLE ACM BACKTEST")
        appendLine("Status: " + if (complete) "COMPLETE" else "PARTIAL / RUNNING")
        appendLine("Binance.US BTCUSDT; 1-minute OHLC.")
        appendLine("Only 10:00–23:00 America/New_York sessions, DST-aware.")
        appendLine("The 15-minute target = LAST completed 1m close before session starts.")
        appendLine("End = final completed 1m candle CLOSE in the 15-minute session.")
        appendLine("Observation = close of last completed 1m candle at minute N.")
        appendLine("Test 7–14 minutes elapsed (8–1 minutes remaining), UP and DOWN.")
        appendLine("For X = 3,15,30,60, ACM_X = mean((high-low)/open*100) over")
        appendLine("the latest X COMPLETED candles, including candles before market open.")
        appendLine("Threshold at observation = ACM_X * MINUTES LEFT.")
        appendLine("Trigger if absolute % price gap from target >= threshold.")
        appendLine("No fixed 0.05% estimate; the ACM varies with each observation.")
        appendLine("STAY = final close STRICTLY on originally observed side.")
        appendLine("CROSS = finish strictly on opposite side. Exact ties separate.")
        appendLine("NO TOUCH = none of the FUTURE 1m high/low WICKS touch the target.")
        appendLine("One market can trigger at several observation minutes.")
        appendLine("The claimed 95% is a hypothesis, NOT a validated probability.")
        appendLine("Binance.US data is NOT the official Kalshi settlement index.")
        appendLine("Calendar days tested: " + days + "; complete markets: " + markets +
            "; missing markets: " + missing +
            "; valid same-data observations: " + observations)
        appendLine()
        appendLine("=== ALL-WINDOW COMPARISON ===")
        appendLine("WINDOW | EVENTS | % STAY | % NO TOUCH | TIES | SIGNAL FREQUENCY")
        val results = mutableListOf<Pair<Int, Double>>()
        for (window in BtcAcmMath.WINDOWS) {
            val group = buckets.filter { it.window == window }
            val triggered = group.sumOf { it.triggered }
            val eligible = group.sumOf { it.eligible }
            val stay = group.sumOf { it.stay }
            val noTouch = group.sumOf { it.noTouch }
            val tie = group.sumOf { it.tie }
            val stayRate = if (triggered > 0) 100.0 * stay / triggered else Double.NaN
            val strictRate = if (triggered > 0) 100.0 * noTouch / triggered else Double.NaN
            val coverage = if (eligible > 0) 100.0 * triggered / eligible else Double.NaN
            appendLine(String.format(Locale.US,
                "%2d | %d | %s | %s | %d | %s",
                window, triggered, fmt(stayRate), fmt(strictRate), tie, fmt(coverage)))
            if (triggered >= 100) results.add(window to strictRate)
        }
        appendLine()
        if (results.isEmpty())
            appendLine("No method yet has 100 triggered events: insufficient comparison data.")
        else {
            val ranked = results.sortedBy { (_, rate) -> abs(rate - 95.0) }
            appendLine("CLOSEST TO THE PROPOSED 95% *NO-TOUCH* RATE (descriptive):")
            for ((index, item) in ranked.withIndex()) {
                appendLine((index + 1).toString() + ". Last " + item.first +
                    " candles: " + fmt(item.second) +
                    " NO TOUCH, " + fmt(abs(item.second - 95.0)) + " away from 95%.")
            }
            appendLine("This ranking is NOT a prediction accuracy guarantee.")
            appendLine("A method can look good merely because it triggers rarely.")
        }
        appendLine()
        appendLine("=== MINUTE-BY-MINUTE + DIRECTION ===")
        for (elapsed in BtcAcmMath.FIRST_ELAPSED..BtcAcmMath.LAST_ELAPSED) {
            appendLine(elapsed.toString() + " elapsed / " +
                (15 - elapsed) + " left")
            for (side in listOf(1, -1)) {
                appendLine(if (side == 1) " ABOVE TARGET" else " BELOW TARGET")
                for (w in BtcAcmMath.WINDOWS) {
                    val row = buckets.first {
                        it.window == w && it.elapsed == elapsed && it.side == side
                    }
                    if (row.triggered == 0) {
                        appendLine(" " + w + " candles: 0 events / " +
                            row.eligible + " eligible; NO RATE")
                    } else {
                        val avgAcm = row.acmSum / row.triggered
                        appendLine(String.format(Locale.US,
                            " %2d: %d/%d triggered (%.1f%%); ACM %.4f%%; " +
                                "STAY %d/%d=%s; NO TOUCH %d/%d=%s; " +
                                "CROSS %d; TIE %d; TOUCH %d; %s",
                            w, row.triggered, row.eligible, row.frequencyPct,
                            avgAcm, row.stay, row.triggered, fmt(row.stayPct),
                            row.noTouch, row.triggered, fmt(row.noTouchPct),
                            row.cross, row.tie, row.touch,
                            if (row.triggered < 100) "UNDER 100 SAMPLES"
                            else if (row.noTouchPct >= 95.0) "95% NO-TOUCH MET IN SAMPLE"
                            else "95% NO-TOUCH NOT MET"))
                    }
                }
            }
            appendLine()
        }
        appendLine("Results are historical fractions of threshold-trigger events,")
        appendLine("not forward-calibrated probabilities. Candle windows and repeated")
        appendLine("minutes overlap; these observations are not independent.")
        appendLine("If a group has few events, its percentage is unreliable.")
        appendLine("The app made no trades; no result is a guaranteed outcome.")
    }
}
