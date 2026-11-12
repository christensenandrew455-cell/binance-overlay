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
    private var panel: OverlayPanel? = null
    private var engineStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        engine = MarketEngine(scope, { snapshot -> render(snapshot) })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground("Starting Binance.US market monitor")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val symbol = intent?.getStringExtra(EXTRA_SYMBOL)
            ?: prefs.getString(KEY_SYMBOL, "BTCUSDT") ?: "BTCUSDT"
        if (intent?.getBooleanExtra(EXTRA_SHOW_PANEL, false) == true) ensurePanel()
        if (!engineStarted) {
            engineStarted = true
            engine.start(symbol)
        } else if (intent?.hasExtra(EXTRA_SYMBOL) == true) {
            engine.updateSymbol(symbol)
        }
        return START_STICKY
    }

    private fun hidePanel() {
        val old = panel
        panel = null
        try { old?.detach() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        hidePanel()
        if (engineStarted) try { engine.stop() } catch (_: Exception) {}
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensurePanel() {
        if (panel != null) return
        val manager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        panel = OverlayPanel(this, manager) { hidePanel() }
        panel?.attach()
    }

    private fun render(snapshot: EngineSnapshot) {
        panel?.render(snapshot)
        val m = snapshot.metrics
        val flags = listOfNotNull(
            if (m.priceAnomaly == true) "price" else null,
            if (m.volumeAnomaly == true) "volume" else null,
            if (m.momentumAnomaly == true) "momentum" else null,
        )
        val status = if (flags.isEmpty()) "No unusual movement" else "Unusual: " + flags.joinToString(", ")
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildNotification("${snapshot.symbol} • TREND ${m.trend} • EMA99 ${fmtPrice(m.ema99Price)}"),
        )
    }

    private fun startInForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2, Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Binance.US market monitor")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(R.drawable.ic_stat, "STOP MONITORING", stop)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "EMA market monitor", NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
        const val EXTRA_SHOW_PANEL = "show_panel"
        const val ACTION_STOP = "com.decisionoverlay.checker.STOP"
        const val PREFS = "overlay_prefs"
        const val KEY_SYMBOL = "symbol"
        const val KEY_DNT_THRESHOLD = "dnt_threshold"
        const val KEY_TARGET = "target_price"
        const val KEY_STRICTNESS = "strictness_level"
        private const val CHANNEL_ID = "ema_market_monitor"
        private const val NOTIFICATION_ID = 42
    }
}
