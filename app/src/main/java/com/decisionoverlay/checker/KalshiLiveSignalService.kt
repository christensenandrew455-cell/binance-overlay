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

    private var readingSection = -1L
    private val readings = ArrayList<KalshiLiveSignalRules.LiveReading>()
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
        val observedAt = chart.optLong("now",0L)
        if (readingSection != start) {
            readingSection=start
            readings.clear()
        }
        if (now-observedAt !in 0L..7_000L || !chart.optBoolean("live_price",false)) {
            status("Waiting for fresh intraminute BTC price")
            return
        }
        val price=chart.optDouble("latest",Double.NaN)
        val target=chart.optDouble("target",Double.NaN)
        val strength=chart.optDouble("percent",Double.NaN)
        if (!price.isFinite() || !target.isFinite() || target<=0 || !strength.isFinite()) return
        val signed=when {
            price>target -> strength
            price<target -> -strength
            else -> 0.0
        }
        if (observedAt <= (readings.lastOrNull()?.at ?: -1L)) return
        readings.removeAll { observedAt-it.at>30_000L }
        readings.add(KalshiLiveSignalRules.LiveReading(observedAt,signed,price))
        if (prefs.getLong("sent_market_section_v2",-1L)==start) return
        val signal=KalshiLiveSignalRules.matchingFilteredNearBuy(history,readings,start,hour)
        if(signal==null) {
            status("BTC chart | waiting for timed 50 to 55 in an eligible window")
            return
        }
        val finish=start+QUARTER-signal.finishLeft*MINUTE
        val title=if(signal.rule.buyUp) "BUY UP" else "BUY DOWN"
        if (Build.VERSION.SDK_INT>=33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=
                PackageManager.PERMISSION_GRANTED) {
            status("Allow Android notification permission to receive BTC watches")
            return
        }
        synchronized(notificationLock) {
            if (!isRunning() || !prefs.getBoolean(ENABLED,true) ||
                prefs.getLong("sent_market_section_v2",-1L)==start ||
                System.currentTimeMillis()>=finish ||
                System.currentTimeMillis()-observedAt>7_000L) return
            val note=NotificationCompat.Builder(context,SIGNAL_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setContentIntent(viewIntent())
                .setAutoCancel(true).build()
            nm.cancel(EARLY_ID)
            nm.cancel(SIGNAL_ID)
            nm.notify(SIGNAL_ID,note)
            prefs.edit().putLong("sent_market_section_v2",start)
                .putString(LAST_ALERT,title+" | early 55 index; 50-to-55 in "+
                    signal.elapsedMs+" ms; estimated 60 in "+signal.projectedMsTo60+
                    " ms; finish "+signal.finishLeft+"m left; unconfirmed research signal")
                .putString(STATUS,"Last watch: "+title).apply()
        }
    }
}
