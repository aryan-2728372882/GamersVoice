package com.gamervoice.app

import android.app.Application
import android.content.Intent
import android.util.Log

class GamerVoiceApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("GamerVoiceApp", "UNCAUGHT CRASH IN THREAD ${thread.name}", throwable)
            try {
                val intent = Intent(this, CrashReportActivity::class.java).apply {
                    putExtra("extra_stack_trace", Log.getStackTraceString(throwable))
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                // Exit current process cleanly so the isolated :crash activity displays
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            } catch (e: Exception) {
                Log.e("GamerVoiceApp", "Failed to launch CrashReportActivity", e)
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }
}
