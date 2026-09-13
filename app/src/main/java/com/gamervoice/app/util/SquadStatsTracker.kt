package com.gamervoice.app.util

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Squad Stats & Streaks Tracker
 * Tracks cumulative voice time spent in squad rooms and consecutive daily play streaks.
 */
object SquadStatsTracker {

    private const val PREFS_NAME = "gamervoice_squad_stats"
    private const val KEY_TOTAL_MILLIS = "total_squadded_millis"
    private const val KEY_STREAK_DAYS = "daily_squad_streak"
    private const val KEY_LAST_DATE = "last_squad_play_date"

    private var sessionStartTs = 0L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun onSessionStarted(context: Context) {
        sessionStartTs = System.currentTimeMillis()
        recordDailyStreak(context)
    }

    fun onSessionEnded(context: Context) {
        if (sessionStartTs <= 0L) return
        val duration = System.currentTimeMillis() - sessionStartTs
        sessionStartTs = 0L

        if (duration > 5_000L) { // Only log meaningful sessions > 5 seconds
            val prefs = getPrefs(context)
            val currentTotal = prefs.getLong(KEY_TOTAL_MILLIS, 0L)
            prefs.edit().putLong(KEY_TOTAL_MILLIS, currentTotal + duration).apply()
        }
    }

    private fun recordDailyStreak(context: Context) {
        val prefs = getPrefs(context)
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = dateFormat.format(Date())
        val lastDateStr = prefs.getString(KEY_LAST_DATE, null)

        if (todayStr == lastDateStr) {
            // Already counted today
            return
        }

        val currentStreak = prefs.getInt(KEY_STREAK_DAYS, 0)
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = dateFormat.format(cal.time)

        val newStreak = if (lastDateStr == yesterdayStr) {
            currentStreak + 1
        } else {
            1
        }

        prefs.edit()
            .putInt(KEY_STREAK_DAYS, newStreak)
            .putString(KEY_LAST_DATE, todayStr)
            .apply()
    }

    fun getStreakDays(context: Context): Int {
        val prefs = getPrefs(context)
        val streak = prefs.getInt(KEY_STREAK_DAYS, 0)
        val lastDateStr = prefs.getString(KEY_LAST_DATE, null) ?: return 0

        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val todayStr = dateFormat.format(Date())
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStr = dateFormat.format(cal.time)

        return if (lastDateStr == todayStr || lastDateStr == yesterdayStr) {
            streak
        } else {
            0 // Streak broke if skipped a day
        }
    }

    fun getTotalSquaddedHours(context: Context): String {
        val prefs = getPrefs(context)
        val totalMillis = prefs.getLong(KEY_TOTAL_MILLIS, 0L)
        val hours = totalMillis.toDouble() / (1000.0 * 60.0 * 60.0)
        return if (hours < 1.0) {
            val mins = TimeUnit.MILLISECONDS.toMinutes(totalMillis)
            "${mins}m"
        } else {
            String.format(Locale.US, "%.1fh", hours)
        }
    }
}
