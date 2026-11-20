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
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Read-only BTC research alerts: calibrated five-indicator unanimous vote,
 * plus fresh Binance.US BTC/USDT spot depth confirmation. NOT Kalshi bids/
 * asks, and never any trading orders. Old service preference names retained
 * for the existing main-graph notification enable/disable button.
 */
class KalshiLiveSignalService : Service() {
    companion object {
        const val PREFS = "kalshi_live_signals_v1"
        const val ENABLED = "enabled"
        const val STATUS = "status"
        const val LAST_ALERT = "last_alert"
        const val START = "kalshi.live.START"
        const val STOP = "kalshi.live.STOP"
        private const val RUNNING_CHANNEL = "btc_chart_proxy_monitor_v1"
        private const val SIGNAL_CHANNEL = "btc_unanimous_book_signal_v1"
        private const val RUNNING_ID = 7910
        private const val SIGNAL_ID = 8100
        private const val MINUTE = 60_000L
        private const val QUARTER = 15L * MINUTE
    }

    private val active = AtomicBoolean(false)
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val timezone = ZoneId.of("America/New_York")
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(
            RUNNING_CHANNEL, "BTC chart pattern monitoring", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(
            SIGNAL_CHANNEL, "BTC 5-of-5 research alerts",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "BTC unanimous five-indicator and fresh spot book research watches"
            enableVibration(true)
        })
    }

    private fun chartIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, BtcRangeBacktestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun runningNotification(detail: String): Notification =
        NotificationCompat.Builder(this, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC five-indicator watch active")
            .setContentText(detail).setOnlyAlertOnce(true)
            .setOngoing(true).setContentIntent(chartIntent()).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            prefs.edit().putBoolean(ENABLED, false)
                .putString(STATUS, "Notifications disabled").apply()
            active.set(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(SIGNAL_ID)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START && !prefs.getBoolean(ENABLED, true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean(ENABLED, true).apply()
        if (!active.compareAndSet(false, true)) return START_STICKY
        val foreground = runningNotification("Watching five votes and BTC/USDT depth")
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(RUNNING_ID, foreground,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(RUNNING_ID, foreground)
        Thread {
            try {
                while (active.get()) {
                    try { inspect() } catch (e: Exception) {
                        status("BTC signal: " + (e.message ?: "unavailable").take(100))
                    }
                    try { Thread.sleep(7_000L) }
                    catch (_: InterruptedException) { break }
                }
            } finally {
                active.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun status(detail: String) {
        if (!active.get() || !prefs.getBoolean(ENABLED, true)) return
        prefs.edit().putString(STATUS, detail).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RUNNING_ID, runningNotification(detail))
    }

    private fun inspect() {
        if (!prefs.getBoolean(ENABLED, true)) return
        val now = System.currentTimeMillis()
        val section = now / QUARTER * QUARTER
        if (Instant.ofEpochMilli(section).atZone(timezone).hour !in 10..22) {
            status("Research alerts only from 10 AM to 11 PM Eastern")
            return
        }
        val raw = getSharedPreferences(BtcRangeBacktestService.PREFS, MODE_PRIVATE)
            .getString(BtcRangeBacktestService.KEY_SNAPSHOT, null)
        if (raw.isNullOrBlank()) { status("Waiting for BTC chart"); return }
        val chart = JSONObject(raw)
        if (chart.optLong("section", -1L) != section ||
            now - chart.optLong("now", -1L) !in 0L..30_000L) {
            status("Waiting for fresh BTC chart")
            return
        }
        val signal = chart.optJSONObject("five_indicator_signal")
        if (signal == null || !signal.optBoolean("model_ready")) {
            status("Tap SCAN 1000 > Test once to train the live signal")
            return
        }
        val left = signal.optInt("left", -1)
        if (left !in listOf(11, 8, 5)) {
            status("Watching 5/5 signals at 11, 8 and 5 min left")
            return
        }
        val closeAt = signal.optLong("closed_at", -1L)
        if (closeAt != section + (15 - left) * MINUTE ||
            now - closeAt !in 0L..90_000L) {
            status("Waiting for latest fully closed one-minute candle")
            return
        }
        if (prefs.getLong("sent_five_signal_section", -1L) == section) {
            status("Already alerted for this BTC market")
            return
        }
        if (!signal.optBoolean("unanimous")) {
            status("BTC chart | " + left + "m left | five votes not unanimous")
            return
        }
        val up = signal.optBoolean("up")
        val book = BtcSpotOrderBook.fromSnapshot(
            chart.optJSONObject("btc_usdt_order_book"))
        if (book == null || !book.supports(up, now)) {
            val share = if (book == null) "unavailable" else
                String.format(Locale.US, "%.0f%% bids", 100 * book.bidShare)
            status("5/5 " + (if (up) "UP" else "DOWN") +
                " | BTC/USDT book not confirming: " + share)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            status("Allow Android notifications for BUY UP / BUY DOWN watches")
            return
        }
        if (!active.get() || !prefs.getBoolean(ENABLED, true)) return
        val spot = chart.optDouble("latest", Double.NaN)
        val target = chart.optDouble("target", Double.NaN)
        if (!spot.isFinite() || !target.isFinite() || target <= 0.0) {
            status("Waiting for valid spot and target")
            return
        }

        val direction = if (up) "UP" else "DOWN"
        val action = "BUY " + direction + " WATCH"
        val comparedToChart = if (signal.optBoolean("override_chart"))
            "OVERRIDES chart" else "CONFIRMS chart"
        val title = action + " · 5/5 + BOOK"
        val spotText = String.format(Locale.US, "$%,.2f", spot)
        val targetText = String.format(Locale.US, "$%,.2f", target)
        val bookText = String.format(Locale.US, "%.0f%% bid / %.1f bps spread",
            100 * book.bidShare, book.spreadBps)
        val detail = left.toString() + " min left | 5/5 " + direction + " (" +
            comparedToChart + ") | BTC " + spotText + " target " + targetText +
            " | BTC/USDT book " + bookText
        val warning = "RESEARCH ONLY: UP = finish ABOVE BTC target, DOWN = BELOW. " +
            "Spot book is NOT Kalshi YES/NO contract book. Verify contract ask, " +
            "fees and spread before buying; no orders are placed."
        val note = NotificationCompat.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat).setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail + "\n" + warning))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(chartIntent())
            .setAutoCancel(true).build()
        prefs.edit().putLong("sent_five_signal_section", section)
            .putString(LAST_ALERT, title + "\n" + detail + "\n" + warning)
            .putString(STATUS, "Last signal: " + action).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SIGNAL_ID, note)
    }

    override fun onDestroy() {
        active.set(false)
        super.onDestroy()
    }
}
