package com.gamervoice.app.webrtc

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import com.gamervoice.app.util.AppLogger

class SignalingClient(private val listener: SignalingListener) {

    companion object {
        private const val TAG = "SignalingClient"
        private const val SIGNALING_URL = "wss://gamervoice-signaling.onrender.com"
    }

    data class PeerMetadata(
        val peerId: String,
        val name: String = "Gamer",
        val avatar: String = "avatar_1"
    )

    interface SignalingListener {
        fun onConnected()
        fun onDisconnected()
        fun onRoomCreated(roomCode: String, myPeerId: String)
        fun onRoomJoined(roomCode: String, myPeerId: String, existingPeers: List<String>, existingMembers: List<PeerMetadata> = emptyList())
        fun onPeerJoined(peerId: String, name: String = "Gamer", avatar: String = "avatar_1")
        fun onOfferReceived(senderPeerId: String, sdp: String)
        fun onAnswerReceived(senderPeerId: String, sdp: String)
        fun onIceCandidateReceived(senderPeerId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int)
        fun onPeerLeft(peerId: String)
        fun onError(message: String)
        fun onTacticalCalloutReceived(senderPeerId: String, senderName: String, calloutId: String, calloutText: String) {}
        fun onPongReceived(latencyMs: Long) {}
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    var isConnected: Boolean = false
        private set
    var myPeerId: String? = null
        private set
    var currentRoomCode: String? = null
        private set

    fun connect() {
        if (webSocket != null && isConnected) return
        AppLogger.log("WS", "Connecting to signaling server at $SIGNALING_URL...")
        val request = Request.Builder().url(SIGNALING_URL).build()
        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    fun disconnect() {
        AppLogger.log("WS", "Disconnecting from signaling server")
        isConnected = false
        webSocket?.close(1000, "User disconnected")
        webSocket = null
        myPeerId = null
        currentRoomCode = null
    }

    fun createRoom(name: String = "Gamer", avatar: String = "avatar_1") {
        val payload = JSONObject().apply {
            put("type", "create-room")
            put("name", name)
            put("avatar", avatar)
        }
        send(payload.toString())
    }

    fun joinRoom(roomCode: String, name: String = "Gamer", avatar: String = "avatar_1") {
        val payload = JSONObject().apply {
            put("type", "join-room")
            put("roomCode", roomCode.trim().uppercase())
            put("name", name)
            put("avatar", avatar)
        }
        send(payload.toString())
    }

    fun sendOffer(targetPeerId: String, sdp: String) {
        val payload = JSONObject().apply {
            put("type", "offer")
            put("targetPeerId", targetPeerId)
            put("sdp", sdp)
        }
        send(payload.toString())
    }

    fun sendAnswer(targetPeerId: String, sdp: String) {
        val payload = JSONObject().apply {
            put("type", "answer")
            put("targetPeerId", targetPeerId)
            put("sdp", sdp)
        }
        send(payload.toString())
    }

    fun sendIceCandidate(targetPeerId: String, candidate: String, sdpMid: String, sdpMLineIndex: Int) {
        val payload = JSONObject().apply {
            put("type", "ice-candidate")
            put("targetPeerId", targetPeerId)
            put("candidate", candidate)
            put("sdpMid", sdpMid)
            put("sdpMLineIndex", sdpMLineIndex)
        }
        send(payload.toString())
    }

    fun leaveRoom() {
        val payload = JSONObject().apply {
            put("type", "leave-room")
        }
        send(payload.toString())
        currentRoomCode = null
    }

    fun sendTacticalCallout(calloutId: String, calloutText: String) {
        val payload = JSONObject().apply {
            put("type", "tactical-callout")
            put("calloutId", calloutId)
            put("calloutText", calloutText)
        }
        send(payload.toString())
    }

    fun sendPing() {
        val payload = JSONObject().apply {
            put("type", "ping")
            put("timestamp", System.currentTimeMillis())
        }
        send(payload.toString())
    }

    private fun send(message: String) {
        AppLogger.log("WS", "Sending message: $message")
        val ws = webSocket
        if (ws != null && isConnected) {
            ws.send(message)
        } else {
            AppLogger.log("ERROR", "WebSocket not connected, attempting reconnect...")
            Log.e(TAG, "Cannot send, WebSocket is not connected")
            connect()
            listener.onError("Reconnecting to voice server, please retry in a second...")
        }
    }

    private fun createWebSocketListener() = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            isConnected = true
            AppLogger.log("WS", "WebSocket Opened & Ready")
            Log.d(TAG, "WebSocket Opened")
            listener.onConnected()
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            AppLogger.log("WS", "Received: $text")
            Log.d(TAG, "Incoming: $text")
            try {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "room-created" -> {
                        val roomCode = json.getString("roomCode")
                        val peerId = json.getString("peerId")
                        myPeerId = peerId
                        currentRoomCode = roomCode
                        listener.onRoomCreated(roomCode, peerId)
                    }

                    "room-joined" -> {
                        val roomCode = json.getString("roomCode")
                        val peerId = json.getString("peerId")
                        val peersArray = json.optJSONArray("peers") ?: JSONArray()
                        val peers = mutableListOf<String>()
                        for (i in 0 until peersArray.length()) {
                            peers.add(peersArray.getString(i))
                        }

                        val membersArray = json.optJSONArray("members")
                        val members = mutableListOf<PeerMetadata>()
                        if (membersArray != null) {
                            for (i in 0 until membersArray.length()) {
                                val m = membersArray.getJSONObject(i)
                                members.add(
                                    PeerMetadata(
                                        peerId = m.getString("peerId"),
                                        name = m.optString("name", "Gamer"),
                                        avatar = m.optString("avatar", "avatar_1")
                                    )
                                )
                            }
                        }

                        myPeerId = peerId
                        currentRoomCode = roomCode
                        listener.onRoomJoined(roomCode, peerId, peers, members)
                    }

                    "peer-joined" -> {
                        val peerId = json.getString("peerId")
                        val name = json.optString("name", "Gamer")
                        val avatar = json.optString("avatar", "avatar_1")
                        listener.onPeerJoined(peerId, name, avatar)
                    }

                    "offer" -> {
                        val senderPeerId = json.getString("senderPeerId")
                        val sdp = json.getString("sdp")
                        listener.onOfferReceived(senderPeerId, sdp)
                    }

                    "answer" -> {
                        val senderPeerId = json.getString("senderPeerId")
                        val sdp = json.getString("sdp")
                        listener.onAnswerReceived(senderPeerId, sdp)
                    }

                    "ice-candidate" -> {
                        val senderPeerId = json.getString("senderPeerId")
                        val candidate = json.getString("candidate")
                        val sdpMid = json.getString("sdpMid")
                        val sdpMLineIndex = json.getInt("sdpMLineIndex")
                        listener.onIceCandidateReceived(senderPeerId, candidate, sdpMid, sdpMLineIndex)
                    }

                    "peer-left" -> {
                        val peerId = json.getString("peerId")
                        listener.onPeerLeft(peerId)
                    }

                    "pong" -> {
                        val sentTs = json.optLong("timestamp", 0L)
                        if (sentTs > 0) {
                            val rtt = System.currentTimeMillis() - sentTs
                            listener.onPongReceived(rtt)
                        }
                    }

                    "tactical-callout" -> {
                        val senderId = json.optString("senderPeerId", "")
                        val senderName = json.optString("senderName", "Squad Teammate")
                        val cId = json.optString("calloutId", "")
                        val cText = json.optString("calloutText", "")
                        listener.onTacticalCalloutReceived(senderId, senderName, cId, cText)
                    }

                    "error" -> {
                        val errorMsg = json.optString("message", "Unknown error")
                        listener.onError(errorMsg)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing message", e)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            AppLogger.log("WS", "WebSocket Closing: code=$code, reason=$reason")
            Log.d(TAG, "WebSocket Closing: $reason")
            listener.onDisconnected()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            isConnected = false
            AppLogger.log("WS", "WebSocket Closed: code=$code, reason=$reason")
            Log.d(TAG, "WebSocket Closed")
            listener.onDisconnected()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            isConnected = false
            AppLogger.log("ERROR", "WebSocket Failure: ${t.message}", Log.getStackTraceString(t))
            Log.e(TAG, "WebSocket Failure: ${t.message}", t)
            this@SignalingClient.webSocket = null
            listener.onDisconnected()
            listener.onError("Voice server connection failed: ${t.message}")
        }
    }
}
