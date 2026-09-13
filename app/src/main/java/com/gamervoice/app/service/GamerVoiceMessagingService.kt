package com.gamervoice.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.RingtoneManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gamervoice.app.HomeActivity
import com.gamervoice.app.R
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class GamerVoiceMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "GamerVoiceFCM"
        const val CHANNEL_ID = "squad_broadcast_alerts"
        const val TOPIC_ALL_GAMERS = "all_gamers"

        fun subscribeToGlobalTopic() {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(TOPIC_ALL_GAMERS)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.i(TAG, "Subscribed successfully to FCM topic: ")
                        } else {
                            Log.w(TAG, "Failed subscribing to topic ", task.exception)
                        }
                    }
            } catch (e: Throwable) {
                Log.w(TAG, "Error initializing topic subscription", e)
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i(TAG, "New FCM Device Registration Token: ")
        subscribeToGlobalTopic()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.i(TAG, "Push message received from: ")

        // 1. Extract Title and Body from notification or data payload
        val title = remoteMessage.notification?.title
            ?: remoteMessage.data["title"]
            ?: "GamerVoice 🎮"

        val body = remoteMessage.notification?.body
            ?: remoteMessage.data["body"]
            ?: "Your squad is waiting in the lobby!"

        val roomCode = remoteMessage.data["roomCode"]

        showHeadsUpNotification(title, body, roomCode)
    }

    private fun showHeadsUpNotification(title: String, body: String, roomCode: String?) {
        val intent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (!roomCode.isNullOrEmpty()) {
                putExtra("auto_join_room", roomCode)
            }
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            (System.currentTimeMillis() % 10000).toInt(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // High-Importance Channel for Heads-Up Alert over games
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Squad Alerts & Broadcasts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Squad match callouts, room invitations, and community alerts"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 150, 80, 150)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val largeAppIcon = BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeAppIcon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 150, 80, 150))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)

        if (!roomCode.isNullOrEmpty()) {
            val joinActionIntent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("auto_join_room", roomCode)
            }
            val joinPendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000 + 1).toInt(),
                joinActionIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            notificationBuilder.addAction(
                R.drawable.ic_lightning_bolt,
                "JOIN SQUAD 🎮",
                joinPendingIntent
            )
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}
