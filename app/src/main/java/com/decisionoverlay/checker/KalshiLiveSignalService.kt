package com.decisionoverlay.checker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Background pattern watch using ONLY the Binance BTC chart snapshot.
 * No Kalshi network requests, contracts, bids, asks, or trades.
 * The chart's 50-90% scale is NOT a calibrated Kalshi probability.
 */
/** Alert worker hosted by the chart foreground service; no second ongoing notification. */
class KalshiLiveSignalService(private val context: Context, private val isRunning: () -> Boolean) {
    companion object {
        const val PREFS = "kalshi_live_signals_v1"
        const val ENABLED = "enabled"
        const val STATUS = "status"
        const val LAST_ALERT = "last_alert"
        private const val SIGNAL_CHANNEL = "btc_chart_proxy_signal_v1"
        private const val SIGNAL_ID = 8100
        private const val EARLY_ID = 8101
        private const val LAST_EARLY_CLOSE = "sent_early_start_close_v1"
        private val notificationLock = Any()

        fun setEnabled(context: Context, enabled: Boolean) = synchronized(notificationLock) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(ENABLED, enabled).apply()
            if (!enabled) {
                prefs.edit().putString(STATUS, "Off. Enable notifications on the BTC chart.").apply()
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                for (id in SIGNAL_ID..SIGNAL_ID + 14) nm.cancel(id)
            }
        }
        private const val MINUTE = 60_000L
        private const val QUARTER = 15L * MINUTE
    }

    private val tz = ZoneId.of("America/New_York")
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        nm.cancel(7910) // Remove the obsolete separate foreground notification.
        if (!prefs.getBoolean("compact_notification_migrated", false)) {
            for (id in SIGNAL_ID..SIGNAL_ID + 14) nm.cancel(id)
            prefs.edit().putBoolean("compact_notification_migrated", true).apply()
        }
        nm.createNotificationChannel(NotificationChannel(
            SIGNAL_CHANNEL, "Kalshi trade alerts",
            NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Research signals based on chart percentages, not live Kalshi contracts"
            enableVibration(true)
        })
    }

    private fun viewIntent(): PendingIntent = PendingIntent.getActivity(context, 0,
        Intent(context, BtcRangeBacktestActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun tick() {
        if (!isRunning() || !prefs.getBoolean(ENABLED, true)) return
        try { inspect() }
        catch (e: Exception) {
            status("Chart snapshot: " + (e.message ?: e.javaClass.simpleName).take(120))
        }
    }

    private fun status(message: String) = synchronized(notificationLock) {
        if (isRunning() && prefs.getBoolean(ENABLED, true))
            prefs.edit().putString(STATUS, message).apply()
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

        val raw = context.getSharedPreferences(BtcRangeBacktestService.PREFS, Context.MODE_PRIVATE)
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
        if (array == null || array.length() == 0) {
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
            val early = KalshiLiveSignalRules.matchingEarlyWindows(
                history, now - closeOfMinute)
            if (early.isNotEmpty()) {
                notifyEarly(start, closeOfMinute, early)
            } else {
                status("BTC chart | " + latest.left +
                    "m left | no exact-minute pattern with 75%+ historical win rate")
            }
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
        if (!isRunning() || !prefs.getBoolean(ENABLED, true)) return
        val rule = best.rule
        val direction = if (rule.buyUp) "ABOVE TARGET at settlement"
            else "BELOW TARGET at settlement"
        val position = if (price > target) "ABOVE" else "BELOW"
        val title = if (rule.buyUp) "BUY UP" else "BUY DOWN"
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
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) {
            status("Allow Android notification permission to receive BTC watches")
            return
        }
        synchronized(notificationLock) {
            if (!isRunning() || !prefs.getBoolean(ENABLED, true)) return
            prefs.edit().putLong("sent_market_section_v2", start)
                .putString(LAST_ALERT, title + "\n" + detail)
                .putString(STATUS, "Last watch: " + title).apply()
            val note = NotificationCompat.Builder(context, SIGNAL_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(viewIntent())
                .setAutoCancel(true).build()
            // Replace the prior signal, but post each new qualifying signal
            // as a fresh alert so it can pop up again.
            nm.cancel(EARLY_ID)
            nm.cancel(SIGNAL_ID)
            nm.notify(SIGNAL_ID, note)
        }
    }

    private fun notifyEarly(
        marketStart: Long,
        startClose: Long,
        windows: List<KalshiLiveSignalRules.EarlySignal>
    ) = synchronized(notificationLock) {
        if (!isRunning() || !prefs.getBoolean(ENABLED, true) ||
            prefs.getLong("sent_market_section_v2", -1L) == marketStart ||
            prefs.getLong(LAST_EARLY_CLOSE, -1L) >= startClose
        ) return@synchronized
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) return@synchronized

        val now = System.currentTimeMillis()
        val remaining = windows.filter {
            now < startClose + it.rule.gapMinutes * MINUTE
        }
        if (remaining.isEmpty() || now / QUARTER * QUARTER != marketStart)
            return@synchronized

        // All matching starts have the same direction. Combine the 1- and
        // 2-minute possibilities in one warning; don't repeat it every poll.
        val title = "Get ready — possible " + remaining.first().rule.side
        val expiresAt = remaining.maxOf { startClose + it.rule.gapMinutes * MINUTE }
        val note = NotificationCompat.Builder(context, SIGNAL_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setContentIntent(viewIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(expiresAt - now)
            .build()
        nm.cancel(EARLY_ID)
        nm.notify(EARLY_ID, note)
        // Separate from the confirmed buy marker: this never consumes a buy.
        prefs.edit().putLong(LAST_EARLY_CLOSE, startClose)
            .putString(STATUS, title).apply()
    }
}
