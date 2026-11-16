package com.decisionoverlay.checker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The app opens directly into the live signal / historical backtest screen.
 * Legacy BUY TIMER settings and alarm helpers remain for receiver compatibility,
 * but old timer alarms are disabled on app launch (there is no old menu now).
 */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putBoolean(ACTIVE, false).apply()
        val intent = Intent(this, BuyAlarmReceiver::class.java)
        val alarm = PendingIntent.getBroadcast(
            this, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        getSystemService(AlarmManager::class.java).cancel(alarm)
        startActivity(Intent(this, LiveSignalActivity::class.java))
        finish()
    }

    companion object {
        const val PREFS = "buy_timer_settings"
        const val ACTIVE = "alerts_active"
        const val REQUEST_CODE = 15430

        fun nextTriggerTime(now: Long = System.currentTimeMillis()): Long {
            val interval = 15L * 60L * 1000L
            val quarter = 15L * 60L * 1000L
            val base = (now / quarter) * quarter
            val candidate = base + 30_000L
            return if (candidate > now) candidate else base + interval + 30_000L
        }

        fun formatTime(time: Long): String =
            SimpleDateFormat("h:mm:ss a", Locale.US).format(Date(time))

        fun scheduleNext(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (!prefs.getBoolean(ACTIVE, false)) return
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) return
            val intent = Intent(context, BuyAlarmReceiver::class.java)
            val pending = PendingIntent.getBroadcast(
                context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val triggerAt = nextTriggerTime()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            }
        }
    }
}
