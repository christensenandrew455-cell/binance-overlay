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
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Live BTCUSD monitor. Only a single 15-minute section is ever shown.
 * Binance.US klines supply the most recent 60 *completed* one-minute ranges
 * (hidden) and the current section's price. 70% is the user's custom
 * 50%-90% distance scale, not an empirically calibrated probability.
 */
class BtcRangeBacktestService : Service() {
    companion object {
        const val PREFS = "btc_live_chart_v2"
        const val START = "btc.chart.START"
        const val STOP = "btc.chart.STOP"
        const val KEY_SNAPSHOT = "snapshot_btcusd_midpoint_v1"
        private const val CHANNEL_RUNNING = "btc_live_running_v2"
        private const val FOREGROUND_ID = 7801
        private const val MINUTE = 60_000L
        private const val QUARTER = 15L * MINUTE
    }

    private data class Dot(val at: Long, val price: Double)
    private val running = AtomicBoolean(false)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS).build()
    private var section = -1L
    private val points = ArrayList<Dot>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CHANNEL_RUNNING, "Kalshi Watch", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            running.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!running.compareAndSet(false, true)) return START_STICKY
        val note = NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Kalshi Watch")
            .setSilent(true)
            .setShowWhen(false)
            .setContentIntent(openIntent(FOREGROUND_ID))
            .setOnlyAlertOnce(true).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34)
            startForeground(FOREGROUND_ID, note,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else
            startForeground(FOREGROUND_ID, note)
        val alerts = KalshiLiveSignalService(this) { running.get() }
        Thread {
            while (running.get()) {
                alerts.tick()
                try { Thread.sleep(7_000L) } catch (_: InterruptedException) { break }
            }
        }.start()
        Thread {
            while (running.get()) {
                try {
                    update()
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .remove("last_error").apply()
                } catch (e: Exception) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putString("last_error", e.message ?: "Network unavailable").apply()
                }
                try { Thread.sleep(3_500L) } catch (_: InterruptedException) {
                    break
                }
            }
        }.start()
        return START_STICKY
    }

    private fun openIntent(id: Int): PendingIntent =
        PendingIntent.getActivity(this, id,
            Intent(this, BtcRangeBacktestActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun update() {
        val receivedAt = System.currentTimeMillis()
        val open = receivedAt / MINUTE * MINUTE
        val marketStart = receivedAt / QUARTER * QUARTER
        val response = client.newCall(Request.Builder().url(
            "https://api.binance.us/api/v3/klines?symbol=${BtcAcmMath.BTC_SYMBOL}&interval=1m&limit=85")
            .get().build()).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) error("Binance.US HTTP ${res.code}: ${body.take(70)}")
                JSONArray(body)
            }
        val candles = HashMap<Long, BtcAcmMath.Candle>()
        for (i in 0 until response.length()) {
            val row = response.getJSONArray(i)
            val candle = BtcAcmMath.Candle(
                row.getLong(0), row.getDouble(1), row.getDouble(2),
                row.getDouble(3), row.getDouble(4))
            if (candle.valid()) candles[candle.at] = candle
        }
        // Require the new quarter candle to exist before trusting the previous
        // minute as finalized. Never substitute a close or another candle.
        if (candles[marketStart] == null) error("Waiting for new BTC/USD quarter")
        val targetCandle = BtcAcmMath.quarterTargetCandle(candles, marketStart)
            ?: error("Waiting for exact prior BTC/USD minute")
        val target = targetCandle.midpoint
        val completedOpen = open - MINUTE
        val acm = BtcAcmMath.averageRange(candles, completedOpen, 60)
            ?: error("Waiting for 60 completed Binance candles")
        val latest = candles[open]?.close
            ?: candles[completedOpen]?.close
            ?: error("Waiting for current Bitcoin price")
        if (section != marketStart) {
            section = marketStart
            points.clear()
            points.add(Dot(marketStart, target))
        }
        // On service restart, recover completed candles from the current
        // 15-minute session. Never plot the older 60-candle warmup.
        for (t in marketStart until open step MINUTE) {
            val candle = candles[t] ?: continue
            val at = t + MINUTE
            if (at > marketStart && points.none { it.at == at })
                points.add(Dot(at, candle.close))
        }
        points.add(Dot(receivedAt, latest))
        if (points.size > 300) points.subList(1, points.size - 299).clear()
        points.sortBy { it.at }

        val left = ((marketStart + QUARTER - receivedAt).toDouble() /
            MINUTE.toDouble()).coerceIn(0.001, 15.0)
        val modeledPct = BtcChartProxyMath.strength(latest, target, acm, left)
        val chartLevels = BtcChartProxyMath.completedMinuteSamples(
            candles, target, marketStart, completedOpen)
        val json = JSONObject().put("section", marketStart)
            .put("target", target).put("acm", acm)
            .put("symbol", BtcAcmMath.BTC_SYMBOL)
            .put("target_candle_open", targetCandle.at)
            .put("target_high", targetCandle.high).put("target_low", targetCandle.low)
            .put("latest", latest).put("now", receivedAt)
            .put("percent", modeledPct)
        val series = JSONArray()
        for (dot in points) {
            if (dot.at in marketStart..receivedAt)
                series.put(JSONArray().put(dot.at).put(dot.price))
        }
        json.put("points", series)
        // Signed chart levels. The monitor never requests Kalshi contract data.
        val proxy = JSONArray()
        for (sample in chartLevels) {
            proxy.put(JSONArray().put(sample.left).put(sample.signedLevel))
        }
        json.put("signed_chart_levels", proxy)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putString(KEY_SNAPSHOT, json.toString()).apply()

        // Alerts read this snapshot rather than querying Kalshi contracts.
    }

    override fun onDestroy() {
        running.set(false)
        super.onDestroy()
    }
}
