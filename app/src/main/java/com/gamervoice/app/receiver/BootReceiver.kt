package com.gamervoice.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gamervoice.app.util.VipExpiryAlarmScheduler
import com.gamervoice.app.util.SquadAlarmHelper

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON") {
            VipExpiryAlarmScheduler.rescheduleIfNeeded(context)
            SquadAlarmHelper.rescheduleIfEnabled(context)
        }
    }
}