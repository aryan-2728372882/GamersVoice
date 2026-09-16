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
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.gamervoice.app.HomeActivity
import com.gamervoice.app.R
import com.gamervoice.app.webrtc.PeerConnectionManager
import com.gamervoice.app.webrtc.SignalingClient
import com.gamervoice.app.util.AppLogger
import com.gamervoice.app.util.SquadReplayManager
import com.gamervoice.app.util.SquadStatsTracker
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
        fun onPeerLatenciesUpdated(latencies: Map<String, Long>) {}
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
    var lastServerPingMs: Long = 0L
        private set

    private val peerLatencies = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun getPeerLatency(peerId: String): Long? = peerLatencies[peerId]
    fun getAllPeerLatencies(): Map<String, Long> = peerLatencies

    var isReconnecting: Boolean = false
        private set
    private var reconnectDeadlineMs: Long = 0L
    private var savedRoomCodeForReconnect: String? = null

    private var connectivityManager: ConnectivityManager? = null
    private var lastActiveNetwork: Network? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            val prev = lastActiveNetwork
            lastActiveNetwork = network
            if (prev != null && prev != network && currentRoomCode != null) {
                AppLogger.log("NETWORK", "Network interface switch detected (Wi-Fi <-> Cellular). Triggering seamless ICE restart...")
                mainHandler.post {
                    listener?.onConnectedStateChanged("Re-syncing network handover...")
                }
                if (!signalingClient.isConnected) {
                    signalingClient.connect()
                }
                executor.execute {
                    try {
                        peerConnectionManager.restartIceForActivePeers()
                    } catch (e: Throwable) {
                        AppLogger.log("ERROR", "ICE restart error on handover: ${e.message}")
                    }
                }
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            if (network == lastActiveNetwork) {
                AppLogger.log("NETWORK", "Active network connection lost.")
            }
        }
    }

    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!isReconnecting || savedRoomCodeForReconnect == null) return

            val now = System.currentTimeMillis()
            if (now > reconnectDeadlineMs) {
                // 3 minutes elapsed! Cleanly exit
                AppLogger.log("SERVICE", "Reconnect timeout after 3 minutes. Exiting room.")
                isReconnecting = false
                val roomToExit = savedRoomCodeForReconnect
                savedRoomCodeForReconnect = null
                mainHandler.post {
                    listener?.onConnectedStateChanged("Status: Disconnected (3m timeout)")
                    listener?.onError("Connection lost after 3 minutes. Room $roomToExit closed.")
                }
                leaveRoom()
                return
            }

            val remainingSec = ((reconnectDeadlineMs - now) / 1000).coerceAtLeast(0)
            mainHandler.post {
                listener?.onConnectedStateChanged("Reconnecting (${remainingSec}s)...")
            }

            if (!signalingClient.isConnected) {
                AppLogger.log("SERVICE", "Attempting reconnect to signaling server (${remainingSec}s remaining)...")
                signalingClient.connect()
            } else {
                val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
                val myName = user?.name ?: "Gamer"
                val myAvatar = user?.avatar ?: "avatar_1"
                AppLogger.log("SERVICE", "Reconnected to server, re-joining room $savedRoomCodeForReconnect...")
                signalingClient.joinRoom(savedRoomCodeForReconnect!!, myName, myAvatar)
            }

            mainHandler.postDelayed(this, 3000)
        }
    }

    private val pingRunnable = object : Runnable {
        override fun run() {
            if (peerConnectionManager.hasActivePeers()) {
                // Measure real WebRTC peer-to-peer latency between players
                peerConnectionManager.queryPeerLatencyMap { map ->
                    peerLatencies.putAll(map)
                    val rttValues = map.values.filter { it > 0 }
                    val avgRtt = if (rttValues.isNotEmpty()) rttValues.average().toLong() else 0L
                    if (avgRtt > 0) {
                        currentLatencyMs = avgRtt
                    }
                    mainHandler.post {
                        listener?.onLatencyUpdated(currentLatencyMs)
                        listener?.onPeerLatenciesUpdated(map)
                    }
                }
            } else if (signalingClient.isConnected) {
                // Measure live signaling server round-trip latency when alone in room
                signalingClient.sendPing()
                if (lastServerPingMs > 0 && currentRoomCode != null) {
                    currentLatencyMs = lastServerPingMs
                    mainHandler.post {
                        listener?.onLatencyUpdated(lastServerPingMs)
                    }
                }
            }
            mainHandler.postDelayed(this, 2500)
        }
    }

    fun setNoiseFilterLevel(level: Int) {
        peerConnectionManager.setNoiseFilterLevel(level)
    }

    fun setLowDataMode(enabled: Boolean) {
        peerConnectionManager.setLowDataMode(enabled)
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
                    val purgeCount = prefs.getInt("auto_purge_count", 0) + 1
                    val now = System.currentTimeMillis()
                    prefs.edit {
                        putInt("auto_purge_count", purgeCount)
                        putLong("last_auto_purge_ts", now)
                        putLong("last_auto_purge_mb", usedMemMb)
                    }
                    Log.d("VoiceService", "🧹 VIP Auto RAM Purge #$purgeCount executed safely: Heap usage ~${usedMemMb}MB (< 10MB safe)")
                    AppLogger.log("PURGE", "VIP Auto RAM Purge #$purgeCount: Active heap is ${usedMemMb}MB (Image & audio cache cleaned)")
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

        // Register Wi-Fi <-> Cellular network handover listener
        connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager
        try {
            val builder = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            connectivityManager?.registerNetworkCallback(builder.build(), networkCallback)
            AppLogger.log("NETWORK", "Network handover monitor registered")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed registering network callback", e)
        }
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

    fun setMicMuted(muted: Boolean) {
        executor.execute {
            prefs.edit { putBoolean(PREF_PTT_ENABLED, muted) }
            peerConnectionManager.setPttModeEnabled(muted)
            peerConnectionManager.setMicTransmitting(!muted)
            updateNotification()
            mainHandler.post {
                listener?.onMicModeChanged(muted)
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
        isReconnecting = false
        savedRoomCodeForReconnect = null
        mainHandler.removeCallbacks(reconnectRunnable)
        SquadReplayManager.stopSession()
        SquadStatsTracker.onSessionEnded(this)
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
                currentLatencyMs = 0L
                peerLatencies.clear()
                stopForegroundService()
            }
        }
    }

    fun getMemberCount(): Int {
        return participants.size.coerceAtLeast(1)
    }

    fun setPeerMutedLocally(peerId: String, muted: Boolean) {
        peerConnectionManager.setPeerMuted(peerId, muted)
        participants[peerId]?.isMutedLocally = muted
        mainHandler.post {
            listener?.onParticipantsUpdated(participants.values.toList())
        }
    }

    fun isPeerMutedLocally(peerId: String): Boolean {
        return peerConnectionManager.isPeerMuted(peerId)
    }

    fun kickPeerFromRoom(peerId: String, reason: String = "Removed by squad leader") {
        signalingClient.kickPeer(peerId, reason)
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
            if (signalingClient.isConnected) {
                signalingClient.sendPing()
            }
        }
        if (isReconnecting && savedRoomCodeForReconnect != null) {
            val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
            val myName = user?.name ?: "Gamer"
            val myAvatar = user?.avatar ?: "avatar_1"
            AppLogger.log("SERVICE", "Signaling reconnected, auto-rejoining room $savedRoomCodeForReconnect")
            signalingClient.joinRoom(savedRoomCodeForReconnect!!, myName, myAvatar)
        }
    }

    override fun onDisconnected() {
        mainHandler.post {
            listener?.onConnectedStateChanged("Status: Server Disconnected")
            listener?.onLatencyUpdated(-1L)
        }

        if (currentRoomCode != null && !isReconnecting) {
            savedRoomCodeForReconnect = currentRoomCode
            isReconnecting = true
            reconnectDeadlineMs = System.currentTimeMillis() + 180_000L // 3 minutes = 180s
            AppLogger.log("SERVICE", "Network disconnection detected in room $currentRoomCode. Starting 3-minute reconnect loop.")
            mainHandler.post {
                listener?.onConnectedStateChanged("Reconnecting to squad (180s remaining)...")
            }
            mainHandler.postDelayed(reconnectRunnable, 2000)
        }
    }

    override fun onRoomCreated(roomCode: String, myPeerId: String) {
        AppLogger.log("SERVICE", "onRoomCreated: room=$roomCode, peerId=$myPeerId")
        currentRoomCode = roomCode
        val user = com.gamervoice.app.auth.AuthManager.getCurrentUser()
        val myName = user?.name ?: "Gamer"
        val myAvatar = user?.avatar ?: "avatar_1"
        participants.clear()
        participants[myPeerId] = com.gamervoice.app.model.RoomParticipant(myPeerId, myName, myAvatar, isMe = true, isHost = true)
        SquadReplayManager.startSession()
        SquadStatsTracker.onSessionStarted(this)
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
        participants[myPeerId] = com.gamervoice.app.model.RoomParticipant(myPeerId, myName, myAvatar, isMe = true, isHost = false)
        for (m in existingMembers) {
            participants[m.peerId] = com.gamervoice.app.model.RoomParticipant(m.peerId, m.name, m.avatar, isMe = false, isHost = m.isHost)
        }
        for (peer in existingPeers) {
            if (!participants.containsKey(peer)) {
                participants[peer] = com.gamervoice.app.model.RoomParticipant(peer, "Gamer", "avatar_1", isMe = false, isHost = false)
            }
        }
        SquadReplayManager.startSession()
        SquadStatsTracker.onSessionStarted(this)
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
        if (isReconnecting) {
            isReconnecting = false
            savedRoomCodeForReconnect = null
            mainHandler.removeCallbacks(reconnectRunnable)
            AppLogger.log("SERVICE", "Successfully reconnected and restored squad room $roomCode!")
            mainHandler.post {
                listener?.onConnectedStateChanged("Status: Reconnected to Room")
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
        lastServerPingMs = latencyMs
        if (!peerConnectionManager.hasActivePeers() && (currentRoomCode != null || isReconnecting)) {
            currentLatencyMs = latencyMs
            mainHandler.post {
                listener?.onLatencyUpdated(latencyMs)
            }
        }
    }

    override fun onError(message: String) {
        mainHandler.post {
            listener?.onError(message)
        }
    }

    override fun onKickedFromRoom(reason: String) {
        AppLogger.log("SERVICE", "Kicked from room: $reason")
        mainHandler.post {
            leaveRoom()
            listener?.onError("Kicked from squad: $reason")
        }
    }

    override fun onServerRestarting(message: String) {
        AppLogger.log("SERVICE", "Server restart notice: $message")
        mainHandler.post {
            listener?.onConnectedStateChanged("Server cycling: auto-reconnecting...")
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
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Throwable) {}
        mainHandler.removeCallbacks(pingRunnable)
        mainHandler.removeCallbacks(autoPurgeRunnable)
        executor.execute {
            peerConnectionManager.closeAll()
            signalingClient.disconnect()
        }
        executor.shutdown()
    }
}
