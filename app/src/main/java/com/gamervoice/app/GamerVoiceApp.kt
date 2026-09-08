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

        // 1. Initialize Persistent Diagnostic Logger
        com.gamervoice.app.util.AppLogger.init(this)

        // 2. Initialize WebRTC Native Engine at application startup on Main Thread
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

        // 3. Safe Uncaught Exception Logger
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e("GamerVoiceApp", "UNCAUGHT CRASH IN THREAD ${thread.name}: $stackTrace", throwable)
            com.gamervoice.app.util.AppLogger.logCrashSync(thread.name, throwable)

            try {
                getSharedPreferences(PREFS_CRASH, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_LAST_CRASH, stackTrace)
                    .commit()
            } catch (e: Exception) {
                Log.e("GamerVoiceApp", "Failed to persist crash log", e)
            }

            defaultHandler?.uncaughtException(thread, throwable)
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
