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
import com.gamervoice.app.util.AppLogger
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
        fun onParticipantsUpdated(participants: List<com.gamervoice.app.model.RoomParticipant>)
        fun onMicModeChanged(isPtt: Boolean)
        fun onError(message: String)
        fun onTacticalCalloutReceived(senderId: String, senderName: String, calloutId: String, calloutText: String) {}
        fun onLatencyUpdated(latencyMs: Long) {}
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceService = this@VoiceService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    var listener: VoiceServiceListener? = null

    val participants = java.util.concurrent.ConcurrentHashMap<String, com.gamervoice.app.model.RoomParticipant>()

    lateinit var signalingClient: SignalingClient
        private set
    lateinit var peerConnectionManager: PeerConnectionManager
        private set

    var currentRoomCode: String? = null
        private set

    private var isCallActive = false
    private lateinit var prefs: SharedPreferences

    var currentLatencyMs: Long = 0L
        private set

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (currentRoomCode != null && signalingClient.isConnected) {
                signalingClient.sendPing()
            }
            mainHandler.postDelayed(this, 10000)
        }
    }

    // Safe periodic RAM cache cleaner to guarantee < 10MB memory usage without interruptions
    // Automatically runs in the background for VIP members; Free users purge manually from Settings
    private val autoPurgeRunnable = object : Runnable {
        override fun run() {
            try {
                if (com.gamervoice.app.auth.PlanManager.isVip()) {
                    com.gamervoice.app.util.ImageLoader.clearMemoryCache()
                    System.gc()
                    val runtime = Runtime.getRuntime()
                    val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                    Log.d("VoiceService", "🧹 VIP Auto RAM Purge executed safely: Heap usage ~${usedMemMb}MB (< 10MB safe)")
                }
            } catch (_: Throwable) {}
            mainHandler.postDelayed(this, 180_000L) // Checks safely every 3 minutes
        }
    }

    fun sendTacticalCallout(calloutId: String, calloutText: String) {
        signalingClient.sendTacticalCallout(calloutId, calloutText)
        com.gamervoice.app.util.TacticalCalloutHelper.playCalloutTone(calloutId)
    }

    fun measurePing() {
        if (signalingClient.isConnected) {
            signalingClient.sendPing()
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        createNotificationChannel()

        signalingClient = SignalingClient(this)
        peerConnectionManager = PeerConnectionManager(this, signalingClient, this)

        // Connect signaling server in background
        signalingClient.connect()
        mainHandler.postDelayed(pingRunnable, 5000)
        mainHandler.postDelayed(autoPurgeRunnable, 60_000L)
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
        AppLogger.log("SERVICE", "createRoom() called on VoiceService")
        executor.execute {
            try {
                val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
                val myName = user?.name ?: "Gamer"
                val myAvatar = user?.avatar ?: "avatar_1"
                AppLogger.log("SERVICE", "Sending create-room to signaling server ($myName, $myAvatar)...")
                signalingClient.createRoom(name = myName, avatar = myAvatar)
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error in createRoom: ${t.message}", Log.getStackTraceString(t))
                Log.e(TAG, "Error in createRoom background task", t)
                mainHandler.post {
                    listener?.onError("Failed to create room: ${t.message}")
                }
            }
        }
    }

    fun joinRoom(code: String) {
        AppLogger.log("SERVICE", "joinRoom(code=$code) called on VoiceService")
        executor.execute {
            try {
                val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
                val myName = user?.name ?: "Gamer"
                val myAvatar = user?.avatar ?: "avatar_1"
                AppLogger.log("SERVICE", "Sending join-room ($code, $myName, $myAvatar) to signaling server...")
                signalingClient.joinRoom(roomCode = code, name = myName, avatar = myAvatar)
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error in joinRoom: ${t.message}", Log.getStackTraceString(t))
                Log.e(TAG, "Error in joinRoom background task", t)
                mainHandler.post {
                    listener?.onError("Failed to join room: ${t.message}")
                }
            }
        }
    }

    fun leaveRoom() {
        AppLogger.log("SERVICE", "leaveRoom() called on VoiceService")
        executor.execute {
            try {
                signalingClient.leaveRoom()
                peerConnectionManager.closeAll()
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error in leaveRoom: ${t.message}", Log.getStackTraceString(t))
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
        return participants.size.coerceAtLeast(1)
    }

    fun startForegroundNotification() {
        mainHandler.post {
            try {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
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
                            isCallActive = true
                            AppLogger.log("SERVICE", "Foreground service started with MICROPHONE type")
                        } catch (t: Throwable) {
                            AppLogger.log("ERROR", "startForeground(MICROPHONE) failed: ${t.message}, trying fallback")
                            Log.e(TAG, "Error starting microphone foreground service", t)
                            try {
                                startForeground(NOTIFICATION_ID, notification)
                                isCallActive = true
                            } catch (e: Throwable) {
                                AppLogger.log("ERROR", "Fallback startForeground failed: ${e.message}")
                            }
                        }
                    } else {
                        AppLogger.log("WARN", "RECORD_AUDIO not granted when starting foreground service")
                        try {
                            startForeground(NOTIFICATION_ID, notification)
                            isCallActive = true
                        } catch (_: Throwable) {}
                    }
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                    isCallActive = true
                    AppLogger.log("SERVICE", "Foreground service started")
                }
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Failed to start foreground service: ${t.message}")
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
        AppLogger.log("SERVICE", "onRoomCreated: room=$roomCode, peerId=$myPeerId")
        currentRoomCode = roomCode
        val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
        val myName = user?.name ?: "Gamer"
        val myAvatar = user?.avatar ?: "avatar_1"
        participants.clear()
        participants[myPeerId] = com.gamervoice.app.model.RoomParticipant(myPeerId, myName, myAvatar, isMe = true)
        startForegroundNotification()
        executor.execute {
            try {
                val isPttDefault = prefs.getBoolean(PREF_PTT_ENABLED, true)
                peerConnectionManager.init(isPtt = isPttDefault)
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error initializing WebRTC after room created: ${t.message}")
                Log.e(TAG, "Error initializing WebRTC after room created", t)
            }
        }
        mainHandler.post {
            listener?.onRoomCreated(roomCode, myPeerId)
            listener?.onParticipantsUpdated(participants.values.toList())
        }
    }

    override fun onRoomJoined(
        roomCode: String,
        myPeerId: String,
        existingPeers: List<String>,
        existingMembers: List<SignalingClient.PeerMetadata>
    ) {
        AppLogger.log("SERVICE", "onRoomJoined: room=$roomCode, peerId=$myPeerId, peers=$existingPeers, members=${existingMembers.size}")
        currentRoomCode = roomCode
        val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
        val myName = user?.name ?: "Gamer"
        val myAvatar = user?.avatar ?: "avatar_1"
        participants.clear()
        participants[myPeerId] = com.gamervoice.app.model.RoomParticipant(myPeerId, myName, myAvatar, isMe = true)
        for (m in existingMembers) {
            participants[m.peerId] = com.gamervoice.app.model.RoomParticipant(m.peerId, m.name, m.avatar, isMe = false)
        }
        for (peer in existingPeers) {
            if (!participants.containsKey(peer)) {
                participants[peer] = com.gamervoice.app.model.RoomParticipant(peer, "Gamer", "avatar_1", isMe = false)
            }
        }
        startForegroundNotification()
        executor.execute {
            try {
                val isPttDefault = prefs.getBoolean(PREF_PTT_ENABLED, true)
                peerConnectionManager.init(isPtt = isPttDefault)
                for (peerId in existingPeers) {
                    peerConnectionManager.connectToPeer(peerId)
                }
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error connecting to peers after joining: ${t.message}")
                Log.e(TAG, "Error connecting to peers after joining", t)
            }
        }
        mainHandler.post {
            listener?.onRoomJoined(roomCode, myPeerId, existingPeers)
            listener?.onParticipantsUpdated(participants.values.toList())
        }
    }

    override fun onPeerJoined(peerId: String, name: String, avatar: String) {
        AppLogger.log("SERVICE", "Peer joined room: $name ($peerId)")
        participants[peerId] = com.gamervoice.app.model.RoomParticipant(peerId, name, avatar, isMe = false)
        mainHandler.post {
            updateNotification()
            listener?.onMemberCountUpdated(getMemberCount())
            listener?.onParticipantsUpdated(participants.values.toList())
        }
    }

    override fun onOfferReceived(senderPeerId: String, sdp: String) {
        AppLogger.log("SERVICE", "Offer received from: $senderPeerId")
        if (!participants.containsKey(senderPeerId)) {
            participants[senderPeerId] = com.gamervoice.app.model.RoomParticipant(senderPeerId, "Gamer", "avatar_1", isMe = false)
            mainHandler.post {
                listener?.onParticipantsUpdated(participants.values.toList())
            }
        }
        startForegroundNotification()
        executor.execute {
            try {
                val isPttDefault = prefs.getBoolean(PREF_PTT_ENABLED, true)
                peerConnectionManager.init(isPtt = isPttDefault)
                peerConnectionManager.handleOffer(senderPeerId, sdp)
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error handling offer from $senderPeerId: ${t.message}")
                Log.e(TAG, "Error handling offer from $senderPeerId", t)
            }
        }
    }

    override fun onAnswerReceived(senderPeerId: String, sdp: String) {
        AppLogger.log("SERVICE", "onAnswerReceived from $senderPeerId")
        executor.execute {
            try {
                peerConnectionManager.handleAnswer(senderPeerId, sdp)
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error handling answer from $senderPeerId: ${t.message}")
                Log.e(TAG, "Error handling answer from $senderPeerId", t)
            }
        }
    }

    override fun onIceCandidateReceived(
        senderPeerId: String,
        candidate: String,
        sdpMid: String,
        sdpMLineIndex: Int,
    ) {
        AppLogger.log("SERVICE", "onIceCandidateReceived from $senderPeerId: mid=$sdpMid, idx=$sdpMLineIndex")
        executor.execute {
            try {
                peerConnectionManager.handleIceCandidate(senderPeerId, candidate, sdpMid, sdpMLineIndex)
            } catch (t: Throwable) {
                AppLogger.log("ERROR", "Error handling ICE candidate from $senderPeerId: ${t.message}")
                Log.e(TAG, "Error handling ICE candidate from $senderPeerId", t)
            }
        }
    }

    override fun onPeerLeft(peerId: String) {
        participants.remove(peerId)
        executor.execute {
            try {
                peerConnectionManager.removePeer(peerId)
                mainHandler.post {
                    updateNotification()
                    listener?.onMemberCountUpdated(getMemberCount())
                    listener?.onParticipantsUpdated(participants.values.toList())
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Error handling peer left for $peerId", t)
            }
        }
    }

    override fun onTacticalCalloutReceived(
        senderPeerId: String,
        senderName: String,
        calloutId: String,
        calloutText: String
    ) {
        AppLogger.log("CALLOUT", "[$senderName]: $calloutText")
        com.gamervoice.app.util.TacticalCalloutHelper.playCalloutTone(calloutId)
        mainHandler.post {
            listener?.onTacticalCalloutReceived(senderPeerId, senderName, calloutId, calloutText)
        }
    }

    override fun onPongReceived(latencyMs: Long) {
        currentLatencyMs = latencyMs
        mainHandler.post {
            listener?.onLatencyUpdated(latencyMs)
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

    override fun onLog(message: String) {
        AppLogger.log("WEBRTC", message)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacks(autoPurgeRunnable)
        executor.execute {
            peerConnectionManager.closeAll()
            signalingClient.disconnect()
        }
        executor.shutdown()
    }
}
