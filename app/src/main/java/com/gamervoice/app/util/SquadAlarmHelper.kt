package com.gamervoice.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import com.gamervoice.app.service.SquadAlarmReceiver
import java.util.Calendar
import java.util.Locale

/**
 * Squad Alarm Helper
 * Schedules daily "Squad Up at 8 PM" reminders using AlarmManager.
 */
object SquadAlarmHelper {

    private const val PREFS_NAME = "gamervoice_squad_alarms"
    private const val KEY_ALARM_ENABLED = "alarm_enabled"
    private const val KEY_ALARM_HOUR = "alarm_hour"
    private const val KEY_ALARM_MINUTE = "alarm_minute"
    private const val KEY_SAVED_ROOM_CODE = "saved_alarm_room_code"
    const val EXTRA_ROOM_CODE = "extra_room_code"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isAlarmEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_ALARM_ENABLED, false)
    }

    fun getAlarmHour(context: Context): Int {
        return getPrefs(context).getInt(KEY_ALARM_HOUR, 20) // Default: 8 PM
    }

    fun getAlarmMinute(context: Context): Int {
        return getPrefs(context).getInt(KEY_ALARM_MINUTE, 0)
    }

    fun getAlarmTimeString(context: Context): String {
        val h = getAlarmHour(context)
        val m = getAlarmMinute(context)
        val amPm = if (h >= 12) "PM" else "AM"
        val displayHour = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", displayHour, m, amPm)
    }

    fun setSquadAlarm(context: Context, hour: Int = 20, minute: Int = 0, roomCode: String = "") {
        val prefs = getPrefs(context)
        prefs.edit()
            .putBoolean(KEY_ALARM_ENABLED, true)
            .putInt(KEY_ALARM_HOUR, hour)
            .putInt(KEY_ALARM_MINUTE, minute)
            .putString(KEY_SAVED_ROOM_CODE, roomCode)
            .apply()

        scheduleAlarmManager(context, hour, minute, roomCode)
    }

    fun cancelSquadAlarm(context: Context) {
        getPrefs(context).edit().putBoolean(KEY_ALARM_ENABLED, false).apply()
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, SquadAlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleNextDay(context: Context, roomCode: String) {
        if (!isAlarmEnabled(context)) return
        val hour = getAlarmHour(context)
        val minute = getAlarmMinute(context)
        scheduleAlarmManager(context, hour, minute, roomCode)
    }

    private fun scheduleAlarmManager(context: Context, hour: Int, minute: Int, roomCode: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        val intent = Intent(context, SquadAlarmReceiver::class.java).apply {
            putExtra(EXTRA_ROOM_CODE, roomCode)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // If target time has already passed today, schedule for tomorrow
        if (cal.timeInMillis <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pendingIntent)
        }
    }
}
