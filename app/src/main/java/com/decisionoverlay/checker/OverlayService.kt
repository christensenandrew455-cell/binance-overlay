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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var engine: MarketEngine
    private var engineStarted = false
    private var lastAlertDirection: String? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        engine = MarketEngine(scope) { snapshot -> render(snapshot) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Monitoring active • early trend alerts enabled")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val symbol = intent?.getStringExtra(EXTRA_SYMBOL)
            ?: prefs.getString(KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT"
        if (!engineStarted) {
            engineStarted = true
            engine.start(symbol)
        } else if (intent?.hasExtra(EXTRA_SYMBOL) == true) {
            engine.updateSymbol(symbol)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (engineStarted) try { engine.stop() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun render(snapshot: EngineSnapshot) {
        val signal = snapshot.metrics.earlySignal
        val direction = when {
            signal == "EARLY DOWN TREND" -> "DOWN"
            signal == "EARLY UP TREND" -> "UP"
            else -> null
        }

        // Notify once when a new directional signal begins; don't repeat every 5 seconds.
        if (direction == null) {
            lastAlertDirection = null
        } else if (direction != lastAlertDirection) {
            lastAlertDirection = direction
            sendTrendAlert(snapshot, direction)
        }

        // Keep the required foreground-service notification quiet and free of trend calls.
        getSystemService(NotificationManager::class.java).notify(
            FOREGROUND_NOTIFICATION_ID,
            buildForegroundNotification("Monitoring ${snapshot.symbol} • early trend alerts enabled"),
        )
    }

    private fun sendTrendAlert(snapshot: EngineSnapshot, direction: String) {
        val m = snapshot.metrics
        val title = if (direction == "DOWN") "EARLY DOWN TREND • ${snapshot.symbol}" else "EARLY UP TREND • ${snapshot.symbol}"
        val detail = "${m.targetPosition} • Price ${fmtPrice(m.currentPrice)} • Target ${fmtPrice(m.targetPrice)}"
        val notification = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$detail. Early trend warning from changing MACD histogram momentum, EMA 7 slope, candle direction and volume. This is a developing signal, not a guarantee."))
            .setContentIntent(openAppPendingIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, notification)
    }

    private fun startInForeground(text: String) {
        val notification = buildForegroundNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun openAppPendingIntent(): PendingIntent =
        PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun buildForegroundNotification(text: String): Notification {
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, FOREGROUND_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("BTC early trend monitor")
            .setContentText(text)
            .setContentIntent(openAppPendingIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat, "STOP MONITORING", stop)
            .build()
    }

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(FOREGROUND_CHANNEL_ID, "Background monitoring", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Quiet notification while market monitoring runs." },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, "Early trend alerts", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Sound and vibration for early downward trends above target and early upward trends below target."
                    enableVibration(true)
                },
        )
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
        const val ACTION_STOP = "com.decisionoverlay.checker.STOP"
        const val PREFS = "overlay_prefs"
        const val KEY_SYMBOL = "symbol"
        const val KEY_DNT_THRESHOLD = "dnt_threshold"
        const val KEY_TARGET = "target_price"
        const val KEY_STRICTNESS = "strictness_level"
        private const val FOREGROUND_CHANNEL_ID = "market_monitor_background"
        private const val ALERT_CHANNEL_ID = "market_early_trend_alerts"
        private const val FOREGROUND_NOTIFICATION_ID = 42
        private const val ALERT_NOTIFICATION_ID = 43
    }
}
