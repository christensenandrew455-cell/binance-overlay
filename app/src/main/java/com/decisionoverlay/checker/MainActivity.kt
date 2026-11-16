package com.decisionoverlay.checker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var nextAlert: TextView
    private lateinit var stateText: TextView

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enableAlerts() else refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 48, 32, 48)
            setBackgroundColor(0xFF10151D.toInt())
        }
        val title = TextView(this).apply {
            text = "BUY TIMER"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }
        nextAlert = TextView(this).apply {
            textSize = 22f
            setTextColor(0xFFB9C7D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 20)
        }
        stateText = TextView(this).apply {
            textSize = 16f
            setTextColor(0xFF9AA8B8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        val start = Button(this).apply {
            text = "START ALERTS"
            setOnClickListener { enableAlerts() }
        }
        val stop = Button(this).apply {
            text = "STOP ALERTS"
            setOnClickListener {
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ACTIVE, false).apply()
                cancelAlarm()
                refresh()
            }
        }
        val accuracyTest = Button(this).apply {
            text = "HISTORICAL ACCURACY TEST"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, HistoricalAccuracyActivity::class.java))
            }
        }
        val trendTest = Button(this).apply {
            text = "TEST TRENDS"
            setOnClickListener { startActivity(Intent(this@MainActivity, TrendOpportunityActivity::class.java)) }
        }
        root.addView(title)
        root.addView(nextAlert)
        root.addView(stateText)
        root.addView(start)
        root.addView(stop)
        root.addView(accuracyTest)
        root.addView(trendTest)
        root.addView(Button(this).apply {
            text = "LIVE TARGET-CROSS SIGNAL"
            setOnClickListener { startActivity(Intent(this@MainActivity, LiveSignalActivity::class.java)) }
        })
        root.addView(Button(this).apply {
            text = "KALSHI EDGE CHECK"
            setOnClickListener { startActivity(Intent(this@MainActivity, KalshiEdgeActivity::class.java)) }
        })
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val alarmManager = getSystemService(AlarmManager::class.java)
        val active = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ACTIVE, false)
        if (active && (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms())) {
            scheduleNext(this)
        }
        refresh()
    }

    private fun enableAlerts() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val alarmManager = getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ACTIVE, true).apply()
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            return
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(ACTIVE, true).apply()
        scheduleNext(this)
        refresh()
    }

    private fun refresh() {
        val active = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(ACTIVE, false)
        stateText.text = if (active) "Alerts are ON • every 15 minutes at :00:30, :15:30, :30:30 and :45:30" else "Alerts are OFF"
        nextAlert.text = if (active) "NEXT BUY\n${formatTime(nextTriggerTime())}" else "Next alert will be shown here"
    }

    private fun cancelAlarm() {
        val intent = Intent(this, BuyAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(this, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        getSystemService(AlarmManager::class.java).cancel(pending)
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
