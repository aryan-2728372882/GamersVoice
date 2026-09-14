package com.gamervoice.app

import android.app.Application
import android.util.Log
import org.webrtc.PeerConnectionFactory

class GamerVoiceApp : Application() {

    companion object {
        const val PREFS_CRASH = "crash_prefs"
        const val KEY_LAST_CRASH = "key_last_crash"
    }

    override fun onCreate() {
        // 1. Safe Uncaught Exception Logger - Installed FIRST before any other initialization
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val stackTrace = Log.getStackTraceString(throwable)
                Log.e("GamerVoiceApp", "UNCAUGHT CRASH IN THREAD ${thread.name}: $stackTrace", throwable)

                // Persist to SharedPreferences for onResume diagnostic dialog
                try {
                    getSharedPreferences(PREFS_CRASH, android.content.Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_LAST_CRASH, stackTrace)
                        .commit()
                } catch (e: Exception) {
                    Log.e("GamerVoiceApp", "Failed to persist crash log", e)
                }

                // Synchronously log to local diagnostic file
                try {
                    com.gamervoice.app.util.AppLogger.logCrashSync(thread.name, throwable)
                } catch (_: Throwable) {}

                // Launch dedicated :crash process CrashReportActivity so user sees error details instead of ANR
                try {
                    val crashIntent = android.content.Intent(this, CrashReportActivity::class.java).apply {
                        flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                        putExtra("extra_stack_trace", "*** FATAL CRASH in thread [${thread.name}] ***\n${throwable.javaClass.name}: ${throwable.message}\n\n$stackTrace")
                    }
                    startActivity(crashIntent)
                } catch (_: Throwable) {}

            } catch (_: Throwable) {
            } finally {
                // Delegate to default system / Crashlytics handler
                try {
                    defaultHandler?.uncaughtException(thread, throwable)
                } catch (_: Throwable) {}

                // Cleanly kill process to prevent Android ANR (App Not Responding) freeze!
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(10)
            }
        }

        super.onCreate()

        // 2. Initialize Persistent Diagnostic Logger
        try {
            com.gamervoice.app.util.AppLogger.init(this)
        } catch (t: Throwable) {
            Log.e("GamerVoiceApp", "AppLogger initialization failed", t)
        }

        // 3. Initialize WebRTC Native Engine at application startup on Main Thread
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(this)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            Log.i("GamerVoiceApp", "WebRTC PeerConnectionFactory initialized successfully at Application startup")
            com.gamervoice.app.util.AppLogger.log("INIT", "WebRTC Native Engine initialized successfully at startup")
        } catch (t: Throwable) {
            Log.e("GamerVoiceApp", "Failed to initialize WebRTC PeerConnectionFactory at startup", t)
            com.gamervoice.app.util.AppLogger.log("FATAL_CRASH", "WebRTC Native Engine failed to initialize: ${t.message}")
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            // App UI is hidden (user switched to Free Fire / home screen)
            // Immediately drop all cached bitmaps to keep background RAM < 10MB!
            com.gamervoice.app.util.ImageLoader.clearMemoryCache()
            System.gc()
            Log.d("GamerVoiceApp", "onTrimMemory: Bitmaps evicted for background Free Fire gaming, level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.gamervoice.app.util.ImageLoader.clearMemoryCache()
        System.gc()
    }
}
