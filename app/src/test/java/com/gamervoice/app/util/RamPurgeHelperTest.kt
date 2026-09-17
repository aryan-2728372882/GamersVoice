package com.gamervoice.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RamPurgeHelperTest {

    @Test
    fun testIntervals() {
        // VIP Auto-purge must strictly run every 5 minutes (300,000 ms)
        assertEquals(5 * 60 * 1000L, RamPurgeHelper.VIP_INTERVAL_MS)
        assertEquals(300_000L, RamPurgeHelper.VIP_INTERVAL_MS)

        // Free tier must strictly throttle to once every 30 minutes (1,800,000 ms)
        assertEquals(30 * 60 * 1000L, RamPurgeHelper.FREE_INTERVAL_MS)
        assertEquals(1_800_000L, RamPurgeHelper.FREE_INTERVAL_MS)
    }

    @Test
    fun testCooldownCalculations() {
        val lastTs = 1_000_000L
        val elapsedHalf = 15 * 60 * 1000L // 15 minutes elapsed
        val now = lastTs + elapsedHalf

        val remainingMs = RamPurgeHelper.FREE_INTERVAL_MS - elapsedHalf
        assertEquals(15 * 60 * 1000L, remainingMs)
        val remainingMinutes = (remainingMs / 60_000L).coerceAtLeast(1L)
        assertEquals(15L, remainingMinutes)

        val elapsedFull = 31 * 60 * 1000L // 31 minutes elapsed
        val canPurge = elapsedFull >= RamPurgeHelper.FREE_INTERVAL_MS
        assertTrue(canPurge)
    }
}
