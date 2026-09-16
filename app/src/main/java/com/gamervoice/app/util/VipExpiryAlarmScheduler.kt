package com.gamervoice.app.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.gamervoice.app.receiver.VipExpiryAlarmReceiver

object VipExpiryAlarmScheduler {

    private const val REQUEST_CODE = 999
    private const val WARN_HOURS_BEFORE = 24L

    fun schedule(context: Context, expiryTimestampMs: Long) {
        if (expiryTimestampMs <= 0) return
        val warnAt = expiryTimestampMs - (WARN_HOURS_BEFORE * 60 * 60 * 1000L)
        val now = System.currentTimeMillis()
        if (warnAt <= now) return

        val intent = Intent(context, VipExpiryAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warnAt, pending)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, warnAt, pending)
            }
        } catch (e: SecurityException) {
            am.set(AlarmManager.RTC_WAKEUP, warnAt, pending)
        }
    }

    fun rescheduleIfNeeded(context: Context) {
        val prefs = context.getSharedPreferences("gamervoice_plan_prefs", Context.MODE_PRIVATE)
        val isVip = prefs.getBoolean("key_is_vip", false)
        val expTs = prefs.getLong("key_expiry_timestamp", -1L)
        val tierStr = prefs.getString("key_plan_tier", "FREE") ?: "FREE"
        if (isVip && expTs > 0 && tierStr != "LIFETIME") {
            schedule(context, expTs)
        }
    }

    fun cancel(context: Context) {
        val intent = Intent(context, VipExpiryAlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(it)
            it.cancel()
        }
    }
}