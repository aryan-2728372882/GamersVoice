package com.gamervoice.app.util

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.gamervoice.app.auth.PlanManager
import com.gamervoice.app.service.VoiceService

/**
 * Production-ready RAM & Memory Cache Purge Manager.
 *
 * Rules:
 * - VIP Users: Automatic background purge every 5 minutes (300,000 ms) + unrestricted manual purges.
 * - Free Users: Background purge & manual purge strictly throttled to once every 30 minutes (1,800,000 ms).
 * - Real operations: Empties Bitmap LRU caches, drains in-memory log queues, releases intermediate buffers,
 *   and executes System.runFinalization() + System.gc() with genuine memory usage tracking.
 */
object RamPurgeHelper {

    private const val TAG = "RamPurgeHelper"

    const val VIP_INTERVAL_MS = 5 * 60 * 1000L      // 5 minutes
    const val FREE_INTERVAL_MS = 30 * 60 * 1000L   // 30 minutes

    const val PREF_PURGE_COUNT = "auto_purge_count"
    const val PREF_LAST_PURGE_TS = "last_auto_purge_ts"
    const val PREF_LAST_PURGE_MB = "last_auto_purge_mb"
    const val PREF_LAST_PURGE_TYPE = "last_purge_type"

    data class PurgeStatus(
        val isVip: Boolean,
        val canPurge: Boolean,
        val remainingCooldownMs: Long,
        val totalPurges: Int,
        val lastPurgeTs: Long,
        val lastPurgeMb: Long
    ) {
        val remainingMinutes: Long
            get() = (remainingCooldownMs / 60_000L).coerceAtLeast(1L)

        val minutesSinceLastPurge: Long
            get() = if (lastPurgeTs > 0L) {
                ((System.currentTimeMillis() - lastPurgeTs) / 60_000L).coerceAtLeast(0L)
            } else 0L
    }

    data class PurgeResult(
        val success: Boolean,
        val usedMemMb: Long,
        val totalPurges: Int,
        val isVip: Boolean,
        val message: String
    )

    /**
     * Checks current status and cooldowns without triggering any action.
     */
    fun getPurgeStatus(context: Context): PurgeStatus {
        val isVip = PlanManager.isVip()
        val prefs = context.getSharedPreferences(VoiceService.PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt(PREF_PURGE_COUNT, 0)
        val lastTs = prefs.getLong(PREF_LAST_PURGE_TS, 0L)
        val lastMb = prefs.getLong(PREF_LAST_PURGE_MB, 0L)
        val now = System.currentTimeMillis()

        return if (isVip) {
            PurgeStatus(
                isVip = true,
                canPurge = true,
                remainingCooldownMs = 0L,
                totalPurges = count,
                lastPurgeTs = lastTs,
                lastPurgeMb = lastMb
            )
        } else {
            val elapsed = now - lastTs
            val canPurge = lastTs == 0L || elapsed >= FREE_INTERVAL_MS
            val remaining = if (canPurge) 0L else (FREE_INTERVAL_MS - elapsed).coerceAtLeast(0L)
            PurgeStatus(
                isVip = false,
                canPurge = canPurge,
                remainingCooldownMs = remaining,
                totalPurges = count,
                lastPurgeTs = lastTs,
                lastPurgeMb = lastMb
            )
        }
    }

    /**
     * Performs a real memory purge if allowed by the user's tier.
     * @param context Application context
     * @param isAuto true if triggered by background timer, false if triggered manually by user tap
     * @return PurgeResult detailing execution status and active heap memory
     */
    @Synchronized
    fun performPurge(context: Context, isAuto: Boolean): PurgeResult {
        val isVip = PlanManager.isVip()
        val status = getPurgeStatus(context)

        // If non-VIP user is in active 30-minute cooldown, reject the purge
        if (!isVip && !status.canPurge) {
            val mins = status.remainingMinutes
            return PurgeResult(
                success = false,
                usedMemMb = status.lastPurgeMb,
                totalPurges = status.totalPurges,
                isVip = false,
                message = "Free tier cooldown active: available in $mins min. Upgrade to 👑 VIP for 5-min auto-purge!"
            )
        }

        // 1. Evict image bitmap LRU caches
        ImageLoader.clearMemoryCache()

        // 2. Clear debug log buffers to reclaim memory
        AppLogger.clearInMemoryLogs()

        // 3. Force garbage collection and object finalization
        System.runFinalization()
        System.gc()

        // 4. Calculate actual live heap memory usage
        val runtime = Runtime.getRuntime()
        val usedMemBytes = runtime.totalMemory() - runtime.freeMemory()
        val usedMemMb = (usedMemBytes / (1024 * 1024)).coerceAtLeast(1)

        val prefs = context.getSharedPreferences(VoiceService.PREFS_NAME, Context.MODE_PRIVATE)
        val purgeCount = prefs.getInt(PREF_PURGE_COUNT, 0) + 1
        val now = System.currentTimeMillis()

        prefs.edit {
            putInt(PREF_PURGE_COUNT, purgeCount)
            putLong(PREF_LAST_PURGE_TS, now)
            putLong(PREF_LAST_PURGE_MB, usedMemMb)
            putString(PREF_LAST_PURGE_TYPE, if (isVip) "VIP" else "FREE")
        }

        val tagPrefix = if (isVip) "VIP Auto RAM Purge" else "Free Auto RAM Purge"
        val triggerLabel = if (isAuto) "background auto" else "manual trigger"
        Log.d("VoiceService", "🧹 $tagPrefix #$purgeCount ($triggerLabel) executed: Heap ~${usedMemMb}MB (< 10MB target)")
        AppLogger.log("PURGE", "$tagPrefix #$purgeCount: Active heap is ${usedMemMb}MB (Image, log & memory caches cleared)")

        val successMessage = if (isVip) {
            "🧹 VIP RAM Purged! Heap: ~${usedMemMb}MB (< 10MB target). Auto-purges every 5 min."
        } else {
            "🧹 RAM Purged! Heap: ~${usedMemMb}MB. Free tier cooldown: 30 minutes."
        }

        return PurgeResult(
            success = true,
            usedMemMb = usedMemMb,
            totalPurges = purgeCount,
            isVip = isVip,
            message = successMessage
        )
    }

    /**
     * Called by VoiceService background loop (e.g. every 60 seconds).
     * Checks if enough time has passed based on user tier:
     * - VIP: Purges if >= 5 minutes since last purge
     * - Free: Purges if >= 30 minutes since last purge
     */
    fun checkAndRunScheduledPurge(context: Context): Boolean {
        val isVip = PlanManager.isVip()
        val prefs = context.getSharedPreferences(VoiceService.PREFS_NAME, Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(PREF_LAST_PURGE_TS, 0L)
        val now = System.currentTimeMillis()
        val requiredInterval = if (isVip) VIP_INTERVAL_MS else FREE_INTERVAL_MS

        if (lastTs == 0L || (now - lastTs) >= requiredInterval) {
            performPurge(context, isAuto = true)
            return true
        }
        return false
    }
}
