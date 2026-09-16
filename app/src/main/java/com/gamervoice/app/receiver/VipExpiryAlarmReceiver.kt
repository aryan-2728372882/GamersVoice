package com.gamervoice.app.receiver

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.gamervoice.app.HomeActivity
import com.gamervoice.app.R

class VipExpiryAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "vip_expiry_alerts"
        const val NOTIFICATION_ID = 8001
    }

    override fun onReceive(context: Context, intent: Intent) {
        ensureNotificationChannel(context)

        val tapIntent = Intent(context, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_tab", 2)
        }
        val pendingTap = PendingIntent.getActivity(
            context, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_vip_crown)
            .setContentTitle("⚠️ VIP Expiring in 24h!")
            .setContentText("Your GamerVoice VIP pass expires soon. Share your referral code to earn 3 free days free!")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Your GamerVoice VIP pass expires in ~24 hours.\n\nShare your squad referral code to earn +3 bonus days FREE and keep your VIP perks alive! 🎮")
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingTap)
            .setColor(0xFF00E5FF.toInt())
            .build()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VIP Expiry Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminds you 24h before your VIP pass expires"
                enableVibration(true)
                vibrationPattern = longArrayOf(0L, 150L, 80L, 150L)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}