package com.decisionoverlay.checker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
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
import java.util.concurrent.TimeUnit

/**
 * Explicitly user-started foreground DATA_SYNC service: it keeps a visible
 * progress notification, scans when the screen is off / another app is open,
 * and sends a distinct success or failure notification. No trades or orders.
 *
 * Like any Android foreground service it cannot run after the user Force Stops
 * the app, and Android may limit very long dataSync service execution.
 */
class BtcMidpointService : Service() {
    companion object {
        const val ACTION_START = "com.decisionoverlay.checker.MIDPOINT_START"
        const val ACTION_STOP = "com.decisionoverlay.checker.MIDPOINT_STOP"
        const val PREFS = "btc_midpoint_test"
        const val KEY_STATUS = "status"
        const val KEY_REPORT = "report"
        const val KEY_RUNNING = "running"
        private const val ACTIVE_CHANNEL = "btc_midpoint_running"
        private const val RESULT_CHANNEL = "btc_midpoint_results"
        private const val ACTIVE_ID = 712
        private const val RESULT_ID = 713
        private val zone = ZoneId.of("America/New_York")

        fun store(service: Service, status: String, report: String? = null,
            running: Boolean? = null) {
            val edit = service.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString(KEY_STATUS, status)
            if (report != null) edit.putString(KEY_REPORT, report)
            if (running != null) edit.putBoolean(KEY_RUNNING, running)
            edit.apply()
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS).build()
    @Volatile private var cancelled = false
    private var workThread: Thread? = null
    private val notificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    override fun onCreate() {
        super.onCreate()
        notificationManager.createNotificationChannel(NotificationChannel(
            ACTIVE_CHANNEL, "BTC historical test progress",
            NotificationManager.IMPORTANCE_LOW
        ))
        notificationManager.createNotificationChannel(NotificationChannel(
            RESULT_CHANNEL, "BTC historical test completed or failed",
            NotificationManager.IMPORTANCE_HIGH
        ))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            cancelled = true
            http.dispatcher.cancelAll()
            workThread?.interrupt()
            store(this, "Stopped by user", running = false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (workThread?.isAlive == true) return START_STICKY
        cancelled = false
        startInForeground("Starting the BTC 0.375% study")
        store(this, "Starting…", report = "", running = true)
        workThread = Thread({ runStudy() }, "BTC midpoint data scan").apply { start() }
        return START_STICKY
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(this, 19,
            Intent(this, BtcMidpointActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(channel: String, title: String,
        text: String, ongoing: Boolean): Notification =
        NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(ongoing)
            .setOnlyAlertOnce(ongoing)
            .setAutoCancel(!ongoing)
            .setPriority(if (ongoing) NotificationCompat.PRIORITY_LOW
                else NotificationCompat.PRIORITY_HIGH)
            .build()

    private fun startInForeground(message: String) {
        val notification = buildNotification(ACTIVE_CHANNEL,
            "BTC study running", message, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            startForeground(ACTIVE_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ACTIVE_ID, notification)
    }

    private fun progress(message: String) {
        if (cancelled) return
        store(this, message, running = true)
        notificationManager.notify(ACTIVE_ID, buildNotification(
            ACTIVE_CHANNEL, "BTC study running", message, true))
    }

    private fun finished(status: String, report: String, success: Boolean) {
        if (cancelled) return
        store(this, status, report = report, running = false)
        notificationManager.notify(RESULT_ID, buildNotification(
            RESULT_CHANNEL,
            if (success) "BTC midpoint study finished" else "BTC midpoint study FAILED",
            status.take(120), false))
    }

    private fun runStudy() {
        val power = getSystemService(PowerManager::class.java)
        val wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "DecisionOverlay:BTCMidpointScan"
        )
        wakeLock.acquire(45L * 60L * 1000L) // auto-expires; released in finally
        try {
            val study = BtcMidpointStudy()
            val now = System.currentTimeMillis()
            val lastCompletedBoundary = now / BtcMidpointStudy.QUARTER *
                BtcMidpointStudy.QUARTER
            var day = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
            var daysScanned = 0
            for (dayIndex in 0 until BtcMidpointStudy.MAX_DAYS) {
                if (cancelled || Thread.currentThread().isInterrupted) return
                // 09:59 prior-minute price plus 10:00..22:59 market candles.
                val start = day.atTime(9, 59).atZone(zone).toInstant().toEpochMilli()
                val marketOpen = day.atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
                val lastOpen = day.atTime(22, 59).atZone(zone).toInstant().toEpochMilli()
                val bars = fetchDay(start, lastOpen)
                daysScanned++
                for (quarter in 0 until 52) {
                    val marketStart = marketOpen + quarter * BtcMidpointStudy.QUARTER
                    if (marketStart + BtcMidpointStudy.QUARTER <= lastCompletedBoundary)
                        study.record(marketStart, bars)
                    if (study.complete()) break
                }
                if (study.complete()) break
                if (dayIndex % 5 == 0) {
                    progress("Days $daysScanned, XX:07 ${study.total(7)}/100, " +
                        "XX:08 ${study.total(8)}/100")
                }
                day = day.minusDays(1)
            }
            if (cancelled) return
            if (study.eligibleMarkets == 0) error(
                "No complete Binance.US BTC markets were returned."
            )
            val enough = study.complete()
            val status = if (enough)
                "100 matches at BOTH XX:07 and XX:08 ($daysScanned days)"
            else "PARTIAL: XX:07 ${study.total(7)}/100, " +
                "XX:08 ${study.total(8)}/100 after $daysScanned days"
            finished(status, study.report(daysScanned,
                if (enough) "COMPLETE" else "PARTIAL / INSUFFICIENT MATCHES"), true)
        } catch (ex: Exception) {
            if (!cancelled) {
                val description = (ex.message ?: ex.javaClass.simpleName).take(260)
                finished("FAILED: $description",
                    "BTC 0.375% study failed.\n$description\n" +
                    "Try again with a working connection; no result was guessed.", false)
            }
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun fetchDay(from: Long, lastMinuteOpen: Long):
        Map<Long, BtcMidpointStudy.Bar> {
        val url = "https://api.binance.us/api/v3/klines?symbol=BTCUSDT" +
            "&interval=1m&startTime=$from&endTime=$lastMinuteOpen" +
            "&limit=1000"
        // Transient connection errors get two retries; authentication is
        // unnecessary for this public Binance.US candles endpoint.
        var lastError: Exception? = null
        for (attempt in 0..2) {
            if (cancelled || Thread.currentThread().isInterrupted)
                throw InterruptedException("Canceled")
            try {
                val request = Request.Builder().url(url).get().build()
                val raw = http.newCall(request).execute().use { response ->
                    val content = response.body?.string().orEmpty()
                    if (!response.isSuccessful) error(
                        "Binance.US HTTP ${response.code}: ${content.take(130)}"
                    )
                    content
                }
                val rows = JSONArray(raw)
                val result = HashMap<Long, BtcMidpointStudy.Bar>(rows.length())
                for (index in 0 until rows.length()) {
                    val row = rows.getJSONArray(index)
                    val timestamp = row.getLong(0)
                    val o = row.getDouble(1)
                    val h = row.getDouble(2)
                    val l = row.getDouble(3)
                    val c = row.getDouble(4)
                    if (o > 0 && h > 0 && l > 0 && c > 0)
                        result[timestamp] = BtcMidpointStudy.Bar(timestamp, o, h, l, c)
                }
                return result
            } catch (ex: Exception) {
                lastError = ex
                if (cancelled) throw ex
                if (attempt < 2) Thread.sleep(650L * (attempt + 1))
            }
        }
        throw IllegalStateException("Cannot load historical BTC day: " +
            (lastError?.message ?: "unknown Binance.US error"), lastError)
    }

    override fun onDestroy() {
        cancelled = true
        http.dispatcher.cancelAll()
        workThread?.interrupt()
        super.onDestroy()
    }
}
