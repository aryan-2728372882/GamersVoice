package com.gamervoice.app.util

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.util.Log

object TacticalCalloutHelper {

    private const val TAG = "TacticalCallout"

    const val ID_ENEMY_SPOTTED = "enemy_spotted"
    const val ID_RUSH_PUSH = "rush_push"
    const val ID_NEED_COVER = "need_cover"
    const val ID_FALL_BACK = "fall_back"

    data class CalloutItem(
        val id: String,
        val label: String,
        val fullText: String,
        val emoji: String,
        val colorHex: String
    )

    val CALLOUTS = listOf(
        CalloutItem(
            id = ID_ENEMY_SPOTTED,
            label = "ENEMY",
            fullText = "Enemy Spotted! 🎯",
            emoji = "🎯",
            colorHex = "#FF5252"
        ),
        CalloutItem(
            id = ID_RUSH_PUSH,
            label = "RUSH",
            fullText = "Rush Now / Push! ⚡",
            emoji = "⚡",
            colorHex = "#00E676"
        ),
        CalloutItem(
            id = ID_NEED_COVER,
            label = "COVER",
            fullText = "Need Cover / Medkit! 🛡️",
            emoji = "🛡️",
            colorHex = "#00E5FF"
        ),
        CalloutItem(
            id = ID_FALL_BACK,
            label = "RETREAT",
            fullText = "Fall Back / Retreat! 🛑",
            emoji = "🛑",
            colorHex = "#FFD700"
        )
    )

    private var toneGenerator: ToneGenerator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        try {
            toneGenerator = ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator init failed, will use fallback", e)
        }
    }

    fun playCalloutTone(calloutId: String) {
        try {
            val tg = toneGenerator ?: ToneGenerator(AudioManager.STREAM_VOICE_CALL, 85).also { toneGenerator = it }
            when (calloutId) {
                ID_ENEMY_SPOTTED -> {
                    // Double high alert chirp
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
                }
                ID_RUSH_PUSH -> {
                    // Ascending fast chirp
                    tg.startTone(ToneGenerator.TONE_PROP_PROMPT, 150)
                }
                ID_NEED_COVER -> {
                    // Quick low tone
                    tg.startTone(ToneGenerator.TONE_PROP_ACK, 200)
                }
                ID_FALL_BACK -> {
                    // Warning warble
                    tg.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 220)
                }
                else -> {
                    tg.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed playing tactical tone", t)
        }
    }

    fun getCallout(id: String): CalloutItem? {
        return CALLOUTS.find { it.id == id }
    }
}
