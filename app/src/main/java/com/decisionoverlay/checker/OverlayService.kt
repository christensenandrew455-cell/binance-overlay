package com.decisionoverlay.checker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class OverlayService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var engine: MarketEngine
    private var panel: OverlayPanel? = null
    private var engineStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val threshold = prefs.getInt(KEY_DNT_THRESHOLD, Scorer.DEFAULT_DNT_THRESHOLD)
        engine = MarketEngine(scope, { snapshot -> main.post { render(snapshot) } }, threshold)
        prefs.registerOnSharedPreferenceChangeListener(thresholdListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground("Starting public Binance data feed")
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val symbol = intent?.getStringExtra(EXTRA_SYMBOL)
            ?: prefs.getString(KEY_SYMBOL, "BTCUSDT")
            ?: "BTCUSDT"

        ensurePanel()
        if (!engineStarted) {
            engineStarted = true
            engine.start(symbol)
        } else if (intent?.hasExtra(EXTRA_SYMBOL) == true) {
            engine.updateSymbol(symbol)
            engine.refreshNow()
        }
        return START_STICKY
    }

    private fun hidePanel() {
        // X means hide only. Leave the service and candle scheduler running.
        // The app's explicit Stop button can stop the service when desired.
        val oldPanel = panel
        panel = null
        try { oldPanel?.detach() } catch (_: Exception) { }
    }

    override fun onDestroy() {
        getSharedPreferences(PREFS, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(thresholdListener)
        val oldPanel = panel
        panel = null
        try { oldPanel?.detach() } catch (_: Exception) { }
        if (engineStarted) {
            try { engine.stop() } catch (_: Exception) { }
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensurePanel() {
        if (panel != null) return
        val windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager
        panel = OverlayPanel(
            context = this,
            windowManager = windowManager,
            // The X only hides the floating window. Keep the foreground service
            // and market engine alive so hiding the badge cannot tear down the app.
            onClose = { hidePanel() },
        )
        panel?.attach()
    }

    private fun render(snapshot: EngineSnapshot) {
        panel?.render(snapshot)
        val text = snapshot.symbol + "  " + snapshot.decision.verdict.name
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
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
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("Trading")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "Floating decision checker", NotificationManager.IMPORTANCE_LOW)
        channel.description = "Keeps the UP / DOWN / DNT floating checker running."
        manager.createNotificationChannel(channel)
    }

    private val thresholdListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == KEY_DNT_THRESHOLD) {
            engine.setDntThreshold(prefs.getInt(KEY_DNT_THRESHOLD, Scorer.DEFAULT_DNT_THRESHOLD))
        }
    }

    companion object {
        const val EXTRA_SYMBOL = "symbol"
        const val PREFS = "overlay_prefs"
        const val KEY_SYMBOL = "symbol"
        const val KEY_DNT_THRESHOLD = "dnt_threshold"
        private const val CHANNEL_ID = "decision_overlay"
        private const val NOTIFICATION_ID = 42
    }
}
