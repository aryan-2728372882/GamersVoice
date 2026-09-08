package com.gamervoice.app.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

object AppLogger {

    data class LogEntry(
        val timestamp: String,
        val tag: String,
        val message: String,
        val details: String? = null
    ) {
        override fun toString(): String {
            return if (details.isNullOrEmpty()) {
                "$timestamp [$tag] $message"
            } else {
                "$timestamp [$tag] $message\n    $details"
            }
        }
    }

    private const val MAX_LOGS = 500
    private const val LOG_FILE_NAME = "gamervoice_debug.log"
    private const val PREV_LOG_FILE_NAME = "gamervoice_prev.log"

    private val logBuffer = CopyOnWriteArrayList<LogEntry>()
    private val listeners = CopyOnWriteArrayList<(LogEntry) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileExecutor = Executors.newSingleThreadExecutor()

    private var logFile: File? = null
    private var previousLogs: String? = null

    fun init(context: Context) {
        try {
            val dir = context.filesDir
            val currentFile = File(dir, LOG_FILE_NAME)
            val prevFile = File(dir, PREV_LOG_FILE_NAME)

            // If current log file exists from previous run, backup to prevFile
            if (currentFile.exists() && currentFile.length() > 0) {
                try {
                    previousLogs = currentFile.readText()
                    currentFile.copyTo(prevFile, overwrite = true)
                    currentFile.delete()
                } catch (e: Exception) {
                    Log.w("AppLogger", "Could not backup previous log file", e)
                }
            }

            logFile = File(dir, LOG_FILE_NAME)
            log("LOGGER", "=== GamerVoice Diagnostic Logging Initialized ===")
            if (!previousLogs.isNullOrEmpty()) {
                log("LOGGER", "Previous session logs found (${previousLogs!!.lines().size} lines). Ready to inspect.")
            }
        } catch (t: Throwable) {
            Log.e("AppLogger", "Failed to initialize AppLogger file persistence", t)
        }
    }

    fun getPreviousSessionLogs(): String? = previousLogs

    fun log(tag: String, message: String, details: String? = null) {
        val time = dateFormat.format(Date())
        val entry = LogEntry(time, tag, message, details)

        logBuffer.add(entry)
        if (logBuffer.size > MAX_LOGS) {
            logBuffer.removeAt(0)
        }

        val logLine = entry.toString()

        when (tag) {
            "ERROR", "FATAL_CRASH", "AUDIO_ERR" -> Log.e("GamerVoice", "[$tag] $message: $details")
            "WARN" -> Log.w("GamerVoice", "[$tag] $message: $details")
            else -> Log.i("GamerVoice", "[$tag] $message: $details")
        }

        // Write to file synchronously and flush immediately so crash logs are never lost
        synchronized(this) {
            try {
                logFile?.let { file ->
                    FileWriter(file, true).use { writer ->
                        writer.append(logLine).append("\n")
                        writer.flush()
                    }
                }
            } catch (_: Exception) {}
        }

        // Notify UI listeners on Main Looper
        mainHandler.post {
            for (listener in listeners) {
                try {
                    listener(entry)
                } catch (_: Throwable) {}
            }
        }
    }

    /**
     * Synchronously write to log file during uncaught crash before process dies
     */
    fun logCrashSync(threadName: String, throwable: Throwable) {
        val time = dateFormat.format(Date())
        val stackTrace = Log.getStackTraceString(throwable)
        val crashText = "\n\n*** FATAL CRASH in Thread [$threadName] at $time ***\n${throwable.javaClass.name}: ${throwable.message}\n$stackTrace\n***************************************************\n"

        val entry = LogEntry(time, "FATAL_CRASH", "Crash in $threadName: ${throwable.message}", stackTrace)
        logBuffer.add(entry)

        try {
            logFile?.let { file ->
                FileWriter(file, true).use { writer ->
                    writer.append(crashText)
                }
            }
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to write crash sync", e)
        }
    }

    fun addListener(listener: (LogEntry) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (LogEntry) -> Unit) {
        listeners.remove(listener)
    }

    fun getAllLogs(): List<LogEntry> = logBuffer.toList()

    fun getAllLogsText(): String {
        val currentLogs = logBuffer.joinToString("\n") { it.toString() }
        return if (!previousLogs.isNullOrEmpty()) {
            "--- PREVIOUS SESSION LOGS ---\n$previousLogs\n\n--- CURRENT SESSION LOGS ---\n$currentLogs"
        } else {
            currentLogs
        }
    }

    fun clear() {
        logBuffer.clear()
        fileExecutor.execute {
            try {
                logFile?.writeText("")
            } catch (_: Exception) {}
        }
        log("INFO", "Log buffer cleared")
    }
}