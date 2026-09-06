package com.gamervoice.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gamervoice.app.HomeActivity
import com.gamervoice.app.R
import com.gamervoice.app.webrtc.PeerConnectionManager
import com.gamervoice.app.webrtc.SignalingClient
import org.webrtc.PeerConnection
import java.util.concurrent.Executors

class VoiceService : Service(),
    SignalingClient.SignalingListener,
    PeerConnectionManager.PeerConnectionListener {

    companion object {
        private const val TAG = "VoiceService"
        const val ACTION_START_FOREGROUND = "com.gamervoice.app.action.START_FOREGROUND"
        const val ACTION_LEAVE_ROOM = "com.gamervoice.app.action.LEAVE_ROOM"
        const val ACTION_TOGGLE_MIC_MODE = "com.gamervoice.app.action.TOGGLE_MIC_MODE"
        const val ACTION_PTT_DOWN = "com.gamervoice.app.action.PTT_DOWN"
        const val ACTION_PTT_UP = "com.gamervoice.app.action.PTT_UP"

        const val PREFS_NAME = "gamervoice_prefs"
        const val PREF_PTT_ENABLED = "pref_ptt_enabled"

        const val CHANNEL_ID = "gamervoice_channel"
        const val NOTIFICATION_ID = 1001
    }

    interface VoiceServiceListener {
        fun onConnectedStateChanged(statusMessage: String)
        fun onRoomCreated(roomCode: String, myPeerId: String)
        fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>)
        fun onMemberCountUpdated(count: Int)
        fun onMicModeChanged(isPtt: Boolean)
        fun onError(message: String)
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    var listener: VoiceServiceListener? = null

    lateinit var signalingClient: SignalingClient
        private set
    lateinit var peerConnectionManager: PeerConnectionManager
        private set

    var currentRoomCode: String? = null
        private set

    private var isCallActive = false
    private lateinit var prefs: SharedPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        createNotificationChannel()

        signalingClient = SignalingClient(this)
        peerConnectionManager = PeerConnectionManager(this, signalingClient, this)

        // Connect signaling server in background
        signalingClient.connect()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_FOREGROUND -> {
                startForegroundNotification()
            }

            ACTION_LEAVE_ROOM -> {
                leaveRoom()
                stopForegroundService()
            }

            ACTION_TOGGLE_MIC_MODE -> {
                toggleMicMode()
            }

            ACTION_PTT_DOWN -> {
                if (peerConnectionManager.isPttModeEnabled()) {
                    peerConnectionManager.setMicTransmitting(true)
                }
            }

            ACTION_PTT_UP -> {
                if (peerConnectionManager.isPttModeEnabled()) {
                    peerConnectionManager.setMicTransmitting(false)
                }
            }
        }
        return START_NOT_STICKY
    }

    fun toggleMicMode() {
        executor.execute {
            val newPttMode = !peerConnectionManager.isPttModeEnabled()
            prefs.edit { putBoolean(PREF_PTT_ENABLED, newPttMode) }
            peerConnectionManager.setPttModeEnabled(newPttMode)
            updateNotification()
            mainHandler.post {
                listener?.onMicModeChanged(newPttMode)
            }
        }
    }

    fun isPttModeEnabled(): Boolean = peerConnectionManager.isPttModeEnabled()

    fun setPttTransmitting(transmitting: Boolean) {
        if (peerConnectionManager.isPttModeEnabled()) {
            peerConnectionManager.setMicTransmitting(transmitting)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    fun createRoom() {
        executor.execute {
            try {
                val isPttDefault = prefs.getBoolean(PREF_PTT_ENABLED, true)
                peerConnectionManager.init(isPtt = isPttDefault)
                signalingClient.createRoom()
            } catch (t: Throwable) {
                Log.e(TAG, "Error in createRoom background task", t)
                mainHandler.post {
                    listener?.onError("Failed to initialize audio call: ${t.message}")
                }
            }
        }
    }

    fun joinRoom(code: String) {
        executor.execute {
            try {
                val isPttDefault = prefs.getBoolean(PREF_PTT_ENABLED, true)
                peerConnectionManager.init(isPtt = isPttDefault)
                signalingClient.joinRoom(code)
            } catch (t: Throwable) {
                Log.e(TAG, "Error in joinRoom background task", t)
                mainHandler.post {
                    listener?.onError("Failed to initialize audio call: ${t.message}")
                }
            }
        }
    }

    fun leaveRoom() {
        executor.execute {
            try {
                signalingClient.leaveRoom()
                peerConnectionManager.closeAll()
            } catch (t: Throwable) {
                Log.e(TAG, "Error leaving room", t)
            }
            mainHandler.post {
                currentRoomCode = null
                isCallActive = false
                stopForegroundService()
            }
        }
    }

    fun getMemberCount(): Int {
        return peerConnectionManager.getActivePeerCount() + 1
    }

    fun startForegroundNotification() {
        mainHandler.post {
            try {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val hasMicPermission = ContextCompat.checkSelfPermission(
                        this,
                        android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (hasMicPermission) {
                        try {
                            startForeground(
                                NOTIFICATION_ID,
                                notification,
                                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
                            )
                        } catch (se: SecurityException) {
                            Log.w(TAG, "SecurityException starting microphone foreground service, fallback", se)
                            startForeground(NOTIFICATION_ID, notification)
                        }
                    } else {
                        Log.w(TAG, "RECORD_AUDIO not granted when starting foreground service")
                        startForeground(NOTIFICATION_ID, notification)
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                isCallActive = true
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to start foreground service", t)
            }
        }
    }

    private fun startForegroundService() {
        startForegroundNotification()
    }

    private fun updateNotification() {
        if (!isCallActive) return
        mainHandler.post {
            try {
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, buildNotification())
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to update notification", t)
            }
        }
    }

    private fun stopForegroundService() {
        mainHandler.post {
            try {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to stop foreground service", t)
            }
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingContentIntent = PendingIntent.getActivity(
            this,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val leaveIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_LEAVE_ROOM
        }
        val pendingLeaveIntent = PendingIntent.getService(
            this,
            1,
            leaveIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val toggleModeIntent = Intent(this, VoiceService::class.java).apply {
            action = ACTION_TOGGLE_MIC_MODE
        }
        val pendingToggleIntent = PendingIntent.getService(
            this,
            2,
            toggleModeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isPtt = peerConnectionManager.isPttModeEnabled()
        val modeLabel = if (isPtt) "Mode: Push-To-Talk" else "Mode: Always On (VAD)"
        val roomText = currentRoomCode ?: "Connecting"
        val countText = "${getMemberCount()}/5 connected"

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GamerVoice ($modeLabel)")
            .setContentText("Room: $roomText | $countText")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingContentIntent)
            .addAction(
                R.drawable.ic_mic_white,
                if (isPtt) "Switch to Always On" else "Switch to PTT",
                pendingToggleIntent,
            )
            .addAction(
                R.drawable.ic_call_end_white,
                "Leave Room",
                pendingLeaveIntent,
            )
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "GamerVoice Voice Call",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Persistent notification for active GamerVoice in-game audio calls"
            setShowBadge(false)
        }
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    // --- SignalingListener Callbacks ---

    override fun onConnected() {
        mainHandler.post {
            listener?.onConnectedStateChanged("Status: Server Connected")
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            listener?.onConnectedStateChanged("Status: Server Disconnected")
        }
    }

    override fun onRoomCreated(roomCode: String, myPeerId: String) {
        mainHandler.post {
            currentRoomCode = roomCode
            startForegroundService()
            listener?.onRoomCreated(roomCode, myPeerId)
        }
    }

    override fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>) {
        mainHandler.post {
            currentRoomCode = roomCode
            startForegroundService()
            listener?.onRoomJoined(roomCode, myPeerId, existingPeers)

            executor.execute {
                for (peerId in existingPeers) {
                    peerConnectionManager.connectToPeer(peerId)
                }
            }
        }
    }

    override fun onPeerJoined(peerId: String) {
        mainHandler.post {
            updateNotification()
            listener?.onMemberCountUpdated(getMemberCount())
        }
    }

    override fun onOfferReceived(senderPeerId: String, sdp: String) {
        executor.execute {
            peerConnectionManager.handleOffer(senderPeerId, sdp)
        }
    }

    override fun onAnswerReceived(senderPeerId: String, sdp: String) {
        executor.execute {
            peerConnectionManager.handleAnswer(senderPeerId, sdp)
        }
    }

    override fun onIceCandidateReceived(
        senderPeerId: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int,
    ) {
        executor.execute {
            peerConnectionManager.handleIceCandidate(senderPeerId, candidate, sdpMid, sdpMLineIndex)
        }
    }

    override fun onPeerLeft(peerId: String) {
        executor.execute {
            peerConnectionManager.removePeer(peerId)
            mainHandler.post {
                updateNotification()
                listener?.onMemberCountUpdated(getMemberCount())
            }
        }
    }

    override fun onError(message: String) {
        mainHandler.post {
            listener?.onError(message)
        }
    }

    // --- PeerConnectionListener Callbacks ---

    override fun onIceConnectionStateChanged(peerId: String, newState: PeerConnection.IceConnectionState) {
        mainHandler.post {
            updateNotification()
            listener?.onMemberCountUpdated(getMemberCount())
        }
    }

    override fun onLog(message: String) {}

    override fun onDestroy() {
        super.onDestroy()
        executor.execute {
            peerConnectionManager.closeAll()
            signalingClient.disconnect()
        }
        executor.shutdown()
    }
}
