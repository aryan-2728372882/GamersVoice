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
        val imageUrl = remoteMessage.notification?.imageUrl?.toString() ?: remoteMessage.data["imageUrl"]
        val actionLabel = remoteMessage.data["actionLabel"] ?: "JOIN SQUAD 🎮"

        showHeadsUpNotification(title, body, roomCode, imageUrl, actionLabel)
    }

    private fun showHeadsUpNotification(
        title: String,
        body: String,
        roomCode: String?,
        imageUrl: String? = null,
        actionLabel: String = "JOIN SQUAD 🎮"
    ) {
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
        val defaultAppLogo = BitmapFactory.decodeResource(resources, R.drawable.app_logo)
        var bannerBitmap: android.graphics.Bitmap? = null

        if (!imageUrl.isNullOrBlank()) {
            try {
                val url = java.net.URL(imageUrl)
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.doInput = true
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                connection.connect()
                val inputStream = connection.inputStream
                bannerBitmap = BitmapFactory.decodeStream(inputStream)
            } catch (t: Throwable) {
                Log.w(TAG, "Could not download push image banner: ${t.message}")
            }
        }

        val notificationBuilder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(defaultAppLogo)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setSound(defaultSoundUri)
            .setVibrate(longArrayOf(0, 150, 80, 150))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)

        if (bannerBitmap != null) {
            notificationBuilder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(bannerBitmap)
                    .bigLargeIcon(null as android.graphics.Bitmap?)
                    .setSummaryText(body)
            )
        } else {
            notificationBuilder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }

        if (!roomCode.isNullOrEmpty() || actionLabel.isNotEmpty()) {
            val joinActionIntent = Intent(this, HomeActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!roomCode.isNullOrEmpty()) {
                    putExtra("auto_join_room", roomCode)
                }
            }
            val joinPendingIntent = PendingIntent.getActivity(
                this,
                (System.currentTimeMillis() % 10000 + 1).toInt(),
                joinActionIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            notificationBuilder.addAction(
                R.drawable.ic_lightning_bolt,
                actionLabel,
                joinPendingIntent
            )
        }

        val notificationId = (System.currentTimeMillis() % 100000).toInt()
        notificationManager.notify(notificationId, notificationBuilder.build())
    }
}
