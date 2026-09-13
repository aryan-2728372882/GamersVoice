package com.gamervoice.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gamervoice.app.HomeActivity
import com.gamervoice.app.R
import com.gamervoice.app.util.SquadAlarmHelper

class SquadAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "gamervoice_squad_alarms"
        const val NOTIFICATION_ID = 2002
    }

    override fun onReceive(context: Context, intent: Intent) {
        val roomCode = intent.getStringExtra(SquadAlarmHelper.EXTRA_ROOM_CODE) ?: ""
        showAlarmNotification(context, roomCode)

        // Reschedule for next day if alarm is set to recurring
        SquadAlarmHelper.rescheduleNextDay(context, roomCode)
    }

    private fun showAlarmNotification(context: Context, roomCode: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Squad Match Alarms",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for scheduled squad gaming matches"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (roomCode.isNotEmpty()) {
                putExtra("auto_join_room", roomCode)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            101,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val bodyText = if (roomCode.isNotEmpty()) {
            "🔥 Squad match time! Room $roomCode is ready. Tap to jump in now!"
        } else {
            "🎮 Squad Up time! Your gaming crew is waiting. Tap to connect!"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("⏰ SQUAD MATCH TIME!")
            .setContentText(bodyText)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}
