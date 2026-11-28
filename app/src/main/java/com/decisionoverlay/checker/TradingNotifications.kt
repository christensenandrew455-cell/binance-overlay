package com.decisionoverlay.checker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

/**
 * Only call these from authenticated trading state updates.
 * A pending order is NOT a win or loss; profit notices require settlement.
 */
object TradingNotifications {
    private const val STATUS_CHANNEL = "kalshi_trading_running"
    private const val PNL_CHANNEL = "kalshi_trading_settlements"
    private const val STATUS_ID = 4181

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun setup(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager(context).createNotificationChannel(
                NotificationChannel(STATUS_CHANNEL, "Trading status", NotificationManager.IMPORTANCE_LOW)
            )
            manager(context).createNotificationChannel(
                NotificationChannel(PNL_CHANNEL, "Trading results", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, KalshiTradingDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Foreground-service notification, only after remote state is confirmed ACTIVE. */
    fun activeNotification(context: Context): Notification {
        setup(context)
        return NotificationCompat.Builder(context, STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Trading active")
            .setContentText("Kalshi BTC 15-minute trading is running")
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    fun showRunning(context: Context) {
        setup(context)
        manager(context).notify(STATUS_ID, activeNotification(context))
    }

    fun showStopped(context: Context, reason: String) {
        setup(context)
        manager(context).cancel(STATUS_ID)
        manager(context).notify(STATUS_ID + 1,
            NotificationCompat.Builder(context, PNL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentTitle("Trading stopped")
                .setContentText(reason.take(120))
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        )
    }

    /** Signed dollar amount supplied by VERIFIED settlement reconciliation only. */
    fun showSettlement(context: Context, signedAmount: String, uniqueTradeId: Int) {
        setup(context)
        require(Regex("^[+-][0-9]+\\.[0-9]{2}$").matches(signedAmount))
        manager(context).notify(4500 + (uniqueTradeId and 0x0fffffff),
            NotificationCompat.Builder(context, PNL_CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(signedAmount)
                .setContentText("Kalshi trade settled")
                .setContentIntent(openApp(context))
                .setAutoCancel(true)
                .build()
        )
    }
}
