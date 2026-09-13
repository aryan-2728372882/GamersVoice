package com.gamervoice.app.util

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Squad Replay Engine
 * VIP Feature: Maintains a rolling 120-second (2-minute) circular PCM buffer in device memory.
 * Allows gamers to export their clutch voice moments into a clean .wav audio file for YouTube Shorts / Reels.
 */
object SquadReplayManager {

    private const val TAG = "SquadReplay"
    const val SAMPLE_RATE = 16000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16
    private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    private const val BYTES_PER_SECOND = SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE // 32,000 bytes/sec
    private const val BUFFER_DURATION_SEC = 120 // 2 minutes rolling buffer
    private const val MAX_BUFFER_SIZE = BYTES_PER_SECOND * BUFFER_DURATION_SEC // 3,840,000 bytes (~3.6MB)

    private const val PREFS_NAME = "gamervoice_audio_prefs"
    private const val KEY_CLUTCH_CONSENT = "pref_clutch_replay_enabled"

    private val lock = ReentrantLock()
    private val circularBuffer = ByteArray(MAX_BUFFER_SIZE)
    private var writePos = 0
    private var isBufferFull = false

    var isReplayConsentActive: Boolean = false
    var isRecordingActive: Boolean = false

    fun init(context: Context) {
        isReplayConsentActive = isConsentGranted(context)
    }

    fun isConsentGranted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CLUTCH_CONSENT, false)
    }

    fun setConsentGranted(context: Context, granted: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CLUTCH_CONSENT, granted)
            .apply()
        isReplayConsentActive = granted
        if (!granted) {
            stopSession()
        }
    }

    fun startSession() {
        if (!isReplayConsentActive) return
        lock.withLock {
            writePos = 0
            isBufferFull = false
            isRecordingActive = true
            Log.i(TAG, "Squad Replay session initialized (120s buffer ready)")
        }
    }

    fun stopSession() {
        lock.withLock {
            isRecordingActive = false
            writePos = 0
            isBufferFull = false
            circularBuffer.fill(0)
            Log.i(TAG, "Squad Replay session stopped and buffer cleared")
        }
    }

    fun appendAudioSamples(data: ByteArray, length: Int) {
        if (!isRecordingActive || !isReplayConsentActive) return

        lock.withLock {
            var srcOffset = 0
            var remaining = length

            while (remaining > 0) {
                val spaceToEnd = MAX_BUFFER_SIZE - writePos
                val bytesToCopy = minOf(remaining, spaceToEnd)

                System.arraycopy(data, srcOffset, circularBuffer, writePos, bytesToCopy)

                writePos += bytesToCopy
                srcOffset += bytesToCopy
                remaining -= bytesToCopy

                if (writePos >= MAX_BUFFER_SIZE) {
                    writePos = 0
                    isBufferFull = true
                }
            }
        }
    }

    fun saveClutchClip(context: Context, callback: (Boolean, String) -> Unit) {
        if (!isConsentGranted(context)) {
            callback(false, "⚠️ Personal Clutch Highlights is disabled. Please enable it in Settings first.")
            return
        }

        if (!com.gamervoice.app.auth.PlanManager.isVip()) {
            callback(false, "👑 Personal Clutch Highlights is a VIP exclusive feature! Upgrade to save your clutch clips.")
            return
        }

        Thread {
            try {
                val totalBytes = if (isBufferFull) MAX_BUFFER_SIZE else writePos
                if (totalBytes < BYTES_PER_SECOND * 3) {
                    callback(false, "Clip too short! Speak a bit longer before saving.")
                    return@Thread
                }

                val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) 
                    ?: File(context.filesDir, "Clips")
                val clipsFolder = File(musicDir, "GamerVoice_Clips")
                if (!clipsFolder.exists()) clipsFolder.mkdirs()

                val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val wavFile = File(clipsFolder, "Clutch_Audio_$timeStamp.wav")

                lock.withLock {
                    FileOutputStream(wavFile).use { fos ->
                        // 1. Write empty 44-byte WAV header placeholder
                        fos.write(ByteArray(44))

                        // 2. Write circular PCM data in chronological order
                        if (isBufferFull) {
                            // First write from writePos to end (oldest data)
                            fos.write(circularBuffer, writePos, MAX_BUFFER_SIZE - writePos)
                            // Then write from 0 to writePos (newest data)
                            fos.write(circularBuffer, 0, writePos)
                        } else {
                            fos.write(circularBuffer, 0, writePos)
                        }
                    }
                }

                // 3. Fill accurate WAV header sizes
                RandomAccessFile(wavFile, "rw").use { raf ->
                    val pcmDataLength = totalBytes.toLong()
                    val totalDataLen = pcmDataLength + 36
                    val byteRate = (SAMPLE_RATE * CHANNELS * BYTES_PER_SAMPLE).toLong()

                    val header = ByteArray(44)
                    header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
                    header[4] = (totalDataLen and 0xff).toByte()
                    header[5] = ((totalDataLen shr 8) and 0xff).toByte()
                    header[6] = ((totalDataLen shr 16) and 0xff).toByte()
                    header[7] = ((totalDataLen shr 24) and 0xff).toByte()
                    header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
                    header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
                    header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0 // 16 for PCM
                    header[20] = 1; header[21] = 0 // PCM format = 1
                    header[22] = CHANNELS.toByte(); header[23] = 0
                    header[24] = (SAMPLE_RATE and 0xff).toByte()
                    header[25] = ((SAMPLE_RATE shr 8) and 0xff).toByte()
                    header[26] = ((SAMPLE_RATE shr 16) and 0xff).toByte()
                    header[27] = ((SAMPLE_RATE shr 24) and 0xff).toByte()
                    header[28] = (byteRate and 0xff).toByte()
                    header[29] = ((byteRate shr 8) and 0xff).toByte()
                    header[30] = ((byteRate shr 16) and 0xff).toByte()
                    header[31] = ((byteRate shr 24) and 0xff).toByte()
                    header[32] = (CHANNELS * BYTES_PER_SAMPLE).toByte(); header[33] = 0 // block align
                    header[34] = BITS_PER_SAMPLE.toByte(); header[35] = 0
                    header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
                    header[40] = (pcmDataLength and 0xff).toByte()
                    header[41] = ((pcmDataLength shr 8) and 0xff).toByte()
                    header[42] = ((pcmDataLength shr 16) and 0xff).toByte()
                    header[43] = ((pcmDataLength shr 24) and 0xff).toByte()

                    raf.seek(0)
                    raf.write(header)
                }

                // 4. Notify Media Scanner
                MediaScannerConnection.scanFile(context, arrayOf(wavFile.absolutePath), arrayOf("audio/wav"), null)

                val durationSec = totalBytes / BYTES_PER_SECOND
                val msg = "🔥 Clutch clip saved! (${durationSec}s saved to ${wavFile.name})"
                Log.i(TAG, "Clutch clip successfully exported to: ${wavFile.absolutePath}")
                callback(true, msg)
            } catch (e: Throwable) {
                Log.e(TAG, "Failed saving clutch clip", e)
                callback(false, "Failed to export clip: ${e.message}")
            }
        }.start()
    }
}
