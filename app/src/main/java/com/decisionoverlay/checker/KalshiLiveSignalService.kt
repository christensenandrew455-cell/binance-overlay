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
 * Background pattern watch using ONLY the Binance BTC chart snapshot.
 * No Kalshi network requests, contracts, bids, asks, or trades.
 * The chart's 50-90% scale is NOT a calibrated Kalshi probability.
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
        private const val SIGNAL_CHANNEL = "btc_chart_proxy_signal_v1"
        private const val RUNNING_ID = 7910
        private const val SIGNAL_ID = 8100
        private const val MINUTE = 60_000L
        private const val QUARTER = 15L * MINUTE
    }

    private val working = AtomicBoolean(false)
    private val tz = ZoneId.of("America/New_York")
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            RUNNING_CHANNEL, "BTC chart pattern monitoring",
            NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(
            SIGNAL_CHANNEL, "BTC chart pattern watch alerts",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Research signals based on chart percentages, not live Kalshi contracts"
            enableVibration(true)
        })
    }

    private fun viewIntent(): PendingIntent = PendingIntent.getActivity(this, 0,
        Intent(this, BtcRangeBacktestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun runningNotification(message: String): Notification =
        NotificationCompat.Builder(this, RUNNING_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC chart pattern watch active")
            .setContentText(message)
            .setContentIntent(viewIntent())
            .setOnlyAlertOnce(true).setOngoing(true).build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            prefs.edit().putBoolean(ENABLED, false)
                .putString(STATUS, "Off. Enable notifications on the BTC chart.").apply()
            working.set(false)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).apply {
                for (id in SIGNAL_ID..SIGNAL_ID + 14) cancel(id)
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action != START && !prefs.getBoolean(ENABLED, true)) {
            stopSelf()
            return START_NOT_STICKY
        }
        prefs.edit().putBoolean(ENABLED, true).apply()
        if (!working.compareAndSet(false, true)) return START_STICKY
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(RUNNING_ID,
                runningNotification("Reading BTC chart percentages"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(RUNNING_ID,
                runningNotification("Reading BTC chart percentages"))
        }
        Thread {
            try {
                while (working.get()) {
                    try { inspect() }
                    catch (e: Exception) {
                        status("Chart snapshot: " +
                            (e.message ?: e.javaClass.simpleName).take(120))
                    }
                    try { Thread.sleep(7_000L) }
                    catch (_: InterruptedException) { break }
                }
            } finally {
                working.set(false)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }.start()
        return START_STICKY
    }

    private fun status(message: String) {
        if (!working.get() || !prefs.getBoolean(ENABLED, true)) return
        prefs.edit().putString(STATUS, message).apply()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(RUNNING_ID, runningNotification(message))
    }

    private fun inspect() {
        if (!prefs.getBoolean(ENABLED, true)) return
        val now = System.currentTimeMillis()
        val start = now / QUARTER * QUARTER
        val hour = Instant.ofEpochMilli(start).atZone(tz).hour
        if (hour !in 10..22) {
            status("Watching 10 AM–11 PM Eastern markets only")
            return
        }

        val raw = getSharedPreferences(BtcRangeBacktestService.PREFS, MODE_PRIVATE)
            .getString(BtcRangeBacktestService.KEY_SNAPSHOT, null)
        if (raw.isNullOrBlank()) {
            status("Waiting for BTC chart")
            return
        }
        val chart = JSONObject(raw)
        if (chart.optLong("section", -1L) != start ||
            now - chart.optLong("now", 0L) !in 0L..30_000L) {
            status("Waiting for fresh BTC chart data")
            return
        }

        // Read the SAME signed, completed-minute levels used by the BTC chart.
        // Old unsigned contract "proxy_quotes" cannot represent below-target data.
        val array = chart.optJSONArray("signed_chart_levels")
        if (array == null || array.length() < 2) {
            status("Waiting for completed signed BTC chart levels")
            return
        }
        val history = ArrayList<BtcChartProxyMath.Sample>()
        for (i in 0 until array.length()) {
            val q = array.optJSONArray(i) ?: continue
            val left = q.optInt(0, -1)
            val level = q.optDouble(1, Double.NaN)
            if (left !in 1..14 || !level.isFinite() ||
                (level != 0.0 && kotlin.math.abs(level) !in 50.0..90.0)) continue
            history.add(BtcChartProxyMath.Sample(left, level))
        }
        val latest = history.minByOrNull { it.left } ?: return
        val closeOfMinute = start + QUARTER - latest.left * MINUTE
        if (now - closeOfMinute !in 0L..90_000L) {
            status("Waiting for the next chart minute close")
            return
        }
        // One actionable research notification at most per 15-minute market.
        // Ordinary foreground service status notifications stay silent.
        if (prefs.getLong("sent_market_section_v2", -1L) == start) {
            status("BTC chart | already alerted in this 15-minute market")
            return
        }
        val matched = KalshiLiveSignalRules.matchingSignedSignals(history)
        if (matched.isEmpty()) {
            status("BTC chart | " + latest.left +
                "m left | no exact-minute pattern with 75%+ historical win rate")
            return
        }
        val price = chart.optDouble("latest", Double.NaN)
        val target = chart.optDouble("target", Double.NaN)
        if (!price.isFinite() || !target.isFinite() || target <= 0.0) {
            status("Waiting for valid BTC spot and target")
            return
        }
        // Check that the live BTC price has not crossed back through target
        // since the qualifying minute closed.
        val best = matched.firstOrNull {
            if (it.rule.buyUp) price > target else price < target
        } ?: run {
            status("BTC chart | pattern matched but price crossed target")
            return
        }
        if (!working.get() || !prefs.getBoolean(ENABLED, true)) return
        val rule = best.rule
        val direction = if (rule.buyUp) "ABOVE TARGET at settlement"
            else "BELOW TARGET at settlement"
        val position = if (price > target) "ABOVE" else "BELOW"
        val title = "BTC pattern: BUY " + rule.side + " (watch)"
        val signedLevel = String.format(Locale.US,
            "%+.0f%%", best.signedChartLevel)
        val spotText = String.format(Locale.US, "$%,.2f", price)
        val targetText = String.format(Locale.US, "$%,.2f", target)
        val distanceText = String.format(Locale.US, "$%,.2f", kotlin.math.abs(price - target))
        val w = best.window
        val stats = String.format(Locale.US,
            "exact-minute %s win %.1f%% (%d/%d) | chart index %.0f | edge +%.1f points | newer %.1f%% (%d hits)",
            rule.side, w.winRatePercent, w.wins, w.hits,
            w.chartSideIndex, w.edgePoints, w.newerWinPercent, w.newerHits)
        val detail = rule.name + " | " + best.startLeft + " -> " + best.left +
            "m left | " + stats + " | BTC " + spotText +
            " (" + distanceText + " " + position + " target " + targetText + ")" +
            " | chart " + signedLevel + " | overall occurrence " +
            String.format(Locale.US, "%.1f%%", rule.hitRatePercent) + ". " + rule.side +
            " means " + direction + ". RESEARCH ONLY: check actual contract ask/fees."
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            status("Allow Android notification permission to receive BTC watches")
            return
        }
        prefs.edit().putLong("sent_market_section_v2", start)
            .putString(LAST_ALERT, title + "\n" + detail)
            .putString(STATUS, "Last watch: " + title).apply()
        val note = NotificationCompat.Builder(this, SIGNAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail +
                "\nThese are Binance-chart outcome rates, not Kalshi odds or expected profit. Check live contract prices and fees manually."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(viewIntent())
            .setAutoCancel(true).build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(SIGNAL_ID, note)
    }

    override fun onDestroy() {
        working.set(false)
        super.onDestroy()
    }
}
