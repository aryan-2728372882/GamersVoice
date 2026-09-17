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
        super.onCreate()

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

            } catch (_: Throwable) {
            } finally {
                // Delegate cleanly to default system handler
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }

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
            // Immediately drop all cached bitmaps and logs to keep background RAM < 10MB!
            com.gamervoice.app.util.ImageLoader.clearMemoryCache()
            com.gamervoice.app.util.AppLogger.clearInMemoryLogs()
            System.runFinalization()
            System.gc()
            Log.d("GamerVoiceApp", "onTrimMemory: Evicted caches for background Free Fire gaming, level=$level")
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        com.gamervoice.app.util.ImageLoader.clearMemoryCache()
        com.gamervoice.app.util.AppLogger.clearInMemoryLogs()
        System.runFinalization()
        System.gc()
    }
}
